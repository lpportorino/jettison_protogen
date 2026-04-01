(ns protodoc.binary-dedup
  "Analyze proto descriptors for binary dedup safety and generate TypeScript tag maps.

   Validates that all JonGUIState subsystem messages have deterministic serialization
   (no map fields anywhere in the message tree). Generates a TypeScript constant mapping
   field names to proto tag numbers for use by the frontend binary dedup scanner.

   Integrated into `make generate` — fails the build with a clear error if any subsystem
   message introduces non-deterministic serialization (e.g., map fields)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [protodoc.manifest :as manifest]
            [taoensso.telemere :as t]))

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
   \".ser.JonGuiDataSystem\" → \"ser.JonGuiDataSystem\""
  [^String type-name]
  (if (str/starts-with? type-name ".")
    (subs type-name 1)
    type-name))

;; ============================================================================
;; Map Field Detection (Recursive)
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
  "Recursively check if a message descriptor contains any map fields.
   Returns the field-name path to the first map field found, or nil if clean.

   Example return: [\"some_field\" \"inner_map\"] meaning
   msg.some_field.inner_map is a map field."
  [msg-index msg-desc visited]
  (let [msg-name (get msg-desc "name")]
    (when-not (contains? visited msg-name)
      (let [visited (conj visited msg-name)
            entries (map-entry-names msg-desc)]
        ;; Check direct map fields first
        (or (when (seq entries)
              (some (fn [field]
                      (when (= "TYPE_MESSAGE" (get field "type"))
                        (let [ref (normalize-type-ref (get field "typeName" ""))
                              short (last (str/split ref #"\."))]
                          (when (contains? entries short)
                            [(get field "name")]))))
                    (get msg-desc "field" [])))
            ;; Recursively check nested TYPE_MESSAGE fields
            (some (fn [field]
                    (when (= "TYPE_MESSAGE" (get field "type"))
                      (let [ref (normalize-type-ref (get field "typeName" ""))
                            short (last (str/split ref #"\."))
                            resolved (when-not (contains? entries short)
                                       (get msg-index ref))]
                        (when resolved
                          (when-let [path (has-map-field? msg-index resolved visited)]
                            (into [(get field "name")] path))))))
                  (get msg-desc "field" [])))))))

;; ============================================================================
;; Subsystem Field Extraction
;; ============================================================================

(defn extract-subsystem-fields
  "Extract non-repeated TYPE_MESSAGE fields with number >= 13 from JonGUIState.
   These are the subsystem embedded messages suitable for binary dedup.
   Excludes repeated fields (like opaque_payloads at tag 8) and scalars (tags 1-7)."
  [gui-state-msg]
  (->> (get gui-state-msg "field" [])
       (filter (fn [f]
                 (and (= "TYPE_MESSAGE" (get f "type"))
                      (>= (get f "number") 13)
                      (not= "LABEL_REPEATED" (get f "label")))))
       (sort-by #(get % "number"))
       (mapv (fn [f]
               {:name      (get f "name")
                :number    (get f "number")
                :type-name (normalize-type-ref (get f "typeName" ""))}))))

;; ============================================================================
;; Determinism Validation
;; ============================================================================

(defn validate-determinism!
  "Validate that all subsystem fields have deterministic serialization.
   Throws ex-info with structured data if any map field is found."
  [msg-index subsystem-fields]
  (doseq [{:keys [name number type-name]} subsystem-fields]
    (when-let [resolved (get msg-index type-name)]
      (when-let [map-path (has-map-field? msg-index resolved #{})]
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
  (let [descriptor (json/read-str (slurp (io/file descriptor-path)))
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
