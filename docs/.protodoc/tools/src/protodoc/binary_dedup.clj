(ns protodoc.binary-dedup
  "Analyze proto descriptors for binary dedup safety and generate TypeScript tag maps.

   Validates that all JonGUIState subsystem messages have deterministic serialization
   (no map fields, no groups, no unknown/future types, and fully-resolvable structure).
   Generates a TypeScript constant mapping field names to proto tag numbers for use by
   the frontend binary dedup scanner.

   Philosophy: **fail-closed whitelist**. A subsystem is allowed through ONLY if every
   field in its transitive subtree is a known-safe scalar/enum OR a resolvable
   TYPE_MESSAGE that itself passes the same check. Anything the validator cannot prove
   safe — including unresolved type refs, TYPE_GROUP, duplicate field numbers, null
   type names, and unrecognized field types — is rejected with a structured error.

   Integrated into `make generate` — fails the build with a clear error if any subsystem
   message introduces non-deterministic or unrecognized serialization."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [protodoc.manifest :as manifest]
            [taoensso.telemere :as t]))

;; ============================================================================
;; Known-safe scalar whitelist (deterministic proto wire types)
;; ============================================================================

(def ^:private safe-scalar-types
  "Proto field types that encode deterministically and do not need recursion.
   Excludes TYPE_MESSAGE (needs recursion) and TYPE_GROUP (unsupported)."
  #{"TYPE_DOUBLE" "TYPE_FLOAT"
    "TYPE_INT64" "TYPE_UINT64" "TYPE_INT32" "TYPE_UINT32"
    "TYPE_FIXED64" "TYPE_FIXED32" "TYPE_SFIXED32" "TYPE_SFIXED64"
    "TYPE_SINT32" "TYPE_SINT64"
    "TYPE_BOOL" "TYPE_STRING" "TYPE_BYTES" "TYPE_ENUM"})

;; ============================================================================
;; Descriptor Navigation
;; ============================================================================

(defn- build-message-index
  "Build a flat map from fully-qualified name to raw message descriptor.
   Includes nested types. Handles arbitrary nesting depth.

   Example: {\"ser.JonGUIState\" {...}
             \"ser.JonGuiDataLrf\" {...}
             \"ser.JonGuiDataLrf.JonGuiDataTarget\" {...}}"
  [descriptor]
  (letfn [(collect [messages package prefix]
            (mapcat (fn [msg]
                      (let [name (get msg "name")
                            fqn (if prefix
                                  (str package "." prefix "." name)
                                  (str package "." name))]
                        (cons [fqn msg]
                              (collect (get msg "nestedType" [])
                                       package
                                       (if prefix (str prefix "." name) name)))))
                    messages))]
    (into {}
          (mapcat (fn [file]
                    (collect (get file "messageType" [])
                             (get file "package" "")
                             nil))
                  (get descriptor "file" [])))))

(defn- normalize-type-ref
  "Strip leading dot from protobuf type references.
   \".ser.JonGuiDataSystem\" → \"ser.JonGuiDataSystem\"

   Throws if the argument is nil — a field with TYPE_MESSAGE but a null typeName
   is a corrupt descriptor that must not silently pass through."
  [type-name]
  (when (nil? type-name)
    (throw (ex-info "Binary dedup: null typeName on TYPE_MESSAGE field"
                    {:type :null-type-name})))
  (let [^String s (str type-name)]
    (if (str/starts-with? s ".")
      (subs s 1)
      s)))

;; ============================================================================
;; Map Field Detection + Whitelist Validation (Recursive, FQN-keyed)
;; ============================================================================

(defn- map-entry-names
  "Return the set of nested type names that are map entry types."
  [msg-desc]
  (into #{}
        (keep (fn [nested]
                (when (get-in nested ["options" "mapEntry"])
                  (get nested "name"))))
        (get msg-desc "nestedType" [])))

(defn has-map-field?
  "Recursively validate a message for binary-dedup safety. Returns the field-name
   path to the first map field found, or nil if the entire subtree is clean.

   This function is fail-closed: any field type the validator cannot classify
   as safe (TYPE_GROUP, unknown types, unresolved message refs, null typeNames)
   triggers an exception. Only maps are reported via the return-value path — all
   other failure modes throw.

   `fqn` is the fully-qualified name of `msg-desc`. Passed in explicitly so that
   the cycle-detection `visited` set can be keyed on FQN rather than the bare
   short name (which would shadow across different scopes).

   Example return: [\"some_field\" \"inner_map\"] meaning
   msg.some_field.inner_map is a map field."
  [msg-index fqn msg-desc visited]
  (when-not (contains? visited fqn)
    (let [visited (conj visited fqn)
          entries (map-entry-names msg-desc)]
      ;; Check direct map fields first
      (or (when (seq entries)
            (some (fn [field]
                    (when (= "TYPE_MESSAGE" (get field "type"))
                      (let [ref   (normalize-type-ref (get field "typeName"))
                            short (last (str/split ref #"\."))]
                        (when (contains? entries short)
                          [(get field "name")]))))
                  (get msg-desc "field" [])))
          ;; Walk all fields — fail-closed on anything we cannot classify
          (some (fn [field]
                  (let [field-type (get field "type")
                        field-name (get field "name")]
                    (cond
                      ;; Safe scalars / enums — no recursion needed
                      (contains? safe-scalar-types field-type)
                      nil

                      ;; Message type — resolve and recurse (must be in the index)
                      (= "TYPE_MESSAGE" field-type)
                      (let [ref   (normalize-type-ref (get field "typeName"))
                            short (last (str/split ref #"\."))]
                        (if (contains? entries short)
                          ;; Direct map field, already handled above
                          nil
                          (if-let [resolved (get msg-index ref)]
                            (when-let [path (has-map-field? msg-index ref resolved visited)]
                              (into [field-name] path))
                            (throw
                              (ex-info
                                (str "Binary dedup: unresolved type reference '" ref "'"
                                     " on field '" field-name "' in '" fqn "'.\n"
                                     "  The descriptor does not contain a definition for this type.\n"
                                     "  Binary dedup cannot verify a subsystem whose structure is unknown.")
                                {:type        :unresolved-type-reference
                                 :type-name   ref
                                 :field-name  field-name
                                 :parent-fqn  fqn})))))

                      ;; Proto2 groups — not supported
                      (= "TYPE_GROUP" field-type)
                      (throw
                        (ex-info
                          (str "Binary dedup: TYPE_GROUP field '" field-name "' in '" fqn "'.\n"
                               "  Proto2 groups have non-deterministic encoding and are not supported.")
                          {:type         :unsupported-type-group
                           :field-name   field-name
                           :parent-fqn   fqn}))

                      ;; Anything else — unknown / future type
                      :else
                      (throw
                        (ex-info
                          (str "Binary dedup: unknown field type '" field-type "' on field '"
                               field-name "' in '" fqn "'.\n"
                               "  The validator whitelist does not include this type.\n"
                               "  If this is a valid new proto type, add it to `safe-scalar-types`"
                               " in binary_dedup.clj after confirming deterministic encoding.")
                          {:type        :unknown-field-type
                           :type-value  field-type
                           :field-name  field-name
                           :parent-fqn  fqn})))))
                (get msg-desc "field" []))))))

;; ============================================================================
;; Subsystem Field Extraction
;; ============================================================================

(defn extract-subsystem-fields
  "Extract non-repeated TYPE_MESSAGE fields with number >= 13 from JonGUIState.
   These are the subsystem embedded messages suitable for binary dedup.
   Excludes repeated fields (like opaque_payloads at tag 8) and scalars (tags 1-7).

   Fail-closed rejections at this level:
     - TYPE_GROUP at any subsystem-level field number >= 13
     - Duplicate field numbers at subsystem-level
     - Unknown field types at subsystem-level"
  [gui-state-msg]
  ;; Scan ALL fields >= 13 for disallowed types before filtering for TYPE_MESSAGE.
  ;; Otherwise a TYPE_GROUP at tag 13 would be silently dropped.
  (doseq [f (get gui-state-msg "field" [])]
    (when (>= (get f "number" 0) 13)
      (let [ft (get f "type")]
        (cond
          (= "TYPE_GROUP" ft)
          (throw
            (ex-info
              (str "Binary dedup: TYPE_GROUP at JonGUIState subsystem field '"
                   (get f "name") "' (number " (get f "number") ").\n"
                   "  Proto2 groups are not supported.")
              {:type         :unsupported-type-group
               :field-name   (get f "name")
               :field-number (get f "number")}))

          ;; TYPE_MESSAGE is handled below. Known scalars at a subsystem number
          ;; are just excluded from extraction (they can't be dedup subsystems).
          (or (= "TYPE_MESSAGE" ft)
              (contains? safe-scalar-types ft))
          nil

          :else
          (throw
            (ex-info
              (str "Binary dedup: unknown field type '" ft "' at JonGUIState field '"
                   (get f "name") "' (number " (get f "number") ").")
              {:type         :unknown-field-type
               :type-value   ft
               :field-name   (get f "name")
               :field-number (get f "number")}))))))
  (let [extracted (->> (get gui-state-msg "field" [])
                       (filter (fn [f]
                                 (and (= "TYPE_MESSAGE" (get f "type"))
                                      (>= (get f "number" 0) 13)
                                      (not= "LABEL_REPEATED" (get f "label")))))
                       (sort-by #(get % "number"))
                       (mapv (fn [f]
                               {:name      (get f "name")
                                :number    (get f "number")
                                :type-name (normalize-type-ref (get f "typeName"))})))
        ;; Fail-closed on duplicate field numbers. Protoc would never emit this
        ;; in a well-formed descriptor, but silent acceptance would cause a key
        ;; collision in the generated TS literal (JavaScript object keys are
        ;; unique by string).
        dups (->> extracted
                  (group-by :number)
                  (filter (fn [[_ vs]] (> (count vs) 1))))]
    (when (seq dups)
      (let [[n vs] (first dups)]
        (throw
          (ex-info
            (str "Binary dedup: duplicate JonGUIState field number " n
                 " used by: " (str/join ", " (map :name vs)) ".\n"
                 "  The generated TypeScript map would silently overwrite one entry.")
            {:type   :duplicate-field-number
             :number n
             :names  (mapv :name vs)}))))
    extracted))

;; ============================================================================
;; Determinism Validation
;; ============================================================================

(defn validate-determinism!
  "Validate that all subsystem fields have deterministic serialization.
   Throws ex-info with structured data if any map field is found, or if any
   of the fail-closed cases in `has-map-field?` trigger."
  [msg-index subsystem-fields]
  (doseq [{:keys [name number type-name]} subsystem-fields]
    (when-let [resolved (get msg-index type-name)]
      (when-let [map-path (has-map-field? msg-index type-name resolved #{})]
        (let [full-path (into [name] map-path)]
          (throw
            (ex-info
              (str "Binary dedup requires deterministic serialization.\n"
                   "  Field path: ser.JonGUIState." (str/join " -> " full-path) "\n"
                   "  Reason: map fields have non-deterministic key ordering.\n"
                   "  Fix: Remove the map field or exclude this subsystem from binary dedup.\n"
                   "  To add support: Add field number " number
                   " to an exclusion set in binary_dedup.clj")
              {:type         :non-deterministic-field
               :path         full-path
               :field-number number
               :type-name    type-name})))))))

;; ============================================================================
;; TypeScript Code Generation
;; ============================================================================

(defn generate-typescript
  "Generate TypeScript source for the binary dedup tag map constant."
  [subsystem-fields]
  (let [entries (mapv (fn [{:keys [name number]}]
                        (str "  " (manifest/snake->camel name) ": " number ","))
                      subsystem-fields)]
    (str "// AUTO-GENERATED by protogen binary-dedup analyzer — DO NOT EDIT\n"
         "// Source: descriptor-set.json (ser.JonGUIState)\n"
         "\n"
         "export const STATE_SUBSYSTEM_TAGS = {\n"
         (str/join "\n" entries) "\n"
         "} as const;\n"
         "\n"
         "export type StateSubsystemKey = keyof typeof STATE_SUBSYSTEM_TAGS;\n"
         "\n"
         "export const STATE_SUBSYSTEM_TAG_SET: ReadonlySet<number> = new Set(\n"
         "  Object.values(STATE_SUBSYSTEM_TAGS)\n"
         ");\n")))

;; ============================================================================
;; Entry Point
;; ============================================================================

(defn generate!
  "Main entry point: parse descriptor, validate determinism, generate TypeScript.

   descriptor-path — path to descriptor-set.json
   output-path     — path to write the generated .ts file"
  [descriptor-path output-path]
  (t/log! :info ["Binary dedup: reading" descriptor-path])
  (let [descriptor (try
                     (json/read-str (slurp (io/file descriptor-path)))
                     (catch Exception e
                       (throw (ex-info
                                (str "Binary dedup: failed to read descriptor "
                                     descriptor-path ": " (.getMessage e))
                                {:type            :descriptor-parse-error
                                 :descriptor-path descriptor-path}
                                e))))
        msg-index  (build-message-index descriptor)

        ;; Find JonGUIState
        gui-state  (get msg-index "ser.JonGUIState")
        _          (when-not gui-state
                     (throw (ex-info "Could not find ser.JonGUIState in descriptor"
                                     {:type :missing-message
                                      :searched-keys (take 10 (keys msg-index))})))

        ;; Extract subsystem fields
        subsystem-fields (extract-subsystem-fields gui-state)
        _                (t/log! :info ["Found" (count subsystem-fields) "subsystem fields:"
                                        (mapv :name subsystem-fields)])

        ;; Validate determinism — throws if map fields found
        _          (validate-determinism! msg-index subsystem-fields)
        _          (t/log! :info ["Determinism validation passed"])

        ;; Generate TypeScript
        ts-content (generate-typescript subsystem-fields)]

    (spit (io/file output-path) ts-content)
    (t/log! :info ["Generated" output-path "with" (count subsystem-fields) "entries"])
    {:fields      subsystem-fields
     :output-path output-path}))
