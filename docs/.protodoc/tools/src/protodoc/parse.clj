(ns protodoc.parse
  "Parse protoc JSON descriptor format into proto-db EDN."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

(def type-mapping
  "Descriptor type name -> the keyword this database records it as.

   ONE KEYWORD PER DESCRIPTOR TYPE, and that is a wire-correctness property
   rather than tidiness. `sint32`/`sint64` are ZIGZAG, the `fixed`/`sfixed`
   family is FIXED-WIDTH, and `int32`/`int64`/`uint32`/`uint64` are varint
   two's-complement: the three families DECODE THE SAME BYTES TO DIFFERENT
   VALUES. A map that folded them onto one keyword left a consumer re-emitting
   a `.proto` from this database declaring a type that reads the wire wrongly,
   with nothing in the database recording that the substitution had happened —
   the defect no downstream check can find, because the information is gone by
   the time it is written.

   `TYPE_GROUP` is preserved distinctly for exactly the same reason and is NOT
   folded onto `:message`. A proto2 group is message-SHAPED but is framed by
   START_GROUP/END_GROUP tags rather than by a length prefix, so emitting a
   plain message field in its place is the same class of silent re-encoding. It
   carries a `:type-ref` like any other reference (see `types-with-type-ref`),
   which is what lets a consumer resolve it and then refuse it KNOWINGLY —
   proto3 has no group syntax, so refusing is the only truthful outcome, and a
   refusal needs the fact to refuse.

   EACH VALUE IS THE TYPE'S `.proto` SPELLING, which is a contract two
   consumers already depend on: `protocol-gen.render` emits a scalar as
   `(name (:type fld))`, and `protodoc.render` prints the same keyword into the
   documentation. `protodoc.parse-test` asserts that spelling per entry rather
   than checking a hand-written list, so the property cannot decay.

   A descriptor type absent here becomes `:unknown` at the field, which every
   consumer is required to refuse rather than guess at."
  {"TYPE_DOUBLE"   :double
   "TYPE_FLOAT"    :float
   "TYPE_INT64"    :int64
   "TYPE_UINT64"   :uint64
   "TYPE_INT32"    :int32
   "TYPE_UINT32"   :uint32
   "TYPE_FIXED64"  :fixed64
   "TYPE_FIXED32"  :fixed32
   "TYPE_SFIXED32" :sfixed32
   "TYPE_SFIXED64" :sfixed64
   "TYPE_SINT32"   :sint32
   "TYPE_SINT64"   :sint64
   "TYPE_BOOL"     :bool
   "TYPE_STRING"   :string
   "TYPE_BYTES"    :bytes
   "TYPE_ENUM"     :enum
   "TYPE_MESSAGE"  :message
   "TYPE_GROUP"    :group})

(def types-with-type-ref
  "The database types whose real type is NAMED rather than spelled out, so the
   field carries a `:type-ref` a consumer resolves against the database.

   `:group` belongs here with `:enum` and `:message`: a group names a message
   in the descriptor exactly as a message field does, and dropping the name
   would leave a distinctly-recorded type nobody can resolve."
  #{:enum :message :group})

(defn- normalize-type-ref
  "Convert .pkg.Type to pkg.Type (remove leading dot)."
  [type-name]
  (when type-name
    (if (str/starts-with? type-name ".")
      (subs type-name 1)
      type-name)))

(defn- parse-number
  "Parse a number from JSON which may be a string (for int64 precision)."
  [v]
  (cond
    (number? v) v
    (string? v) (try
                  (if (str/includes? v ".")
                    (Double/parseDouble v)
                    (Long/parseLong v))
                  (catch Exception _ v))
    :else v))

;; ============================================================================
;; Constraint Registry & Multimethod Dispatch
;; ============================================================================

(def constraint-registry
  "Registry of all known buf.validate constraint handlers.
   Each entry is a keyword of form :type/constraint (e.g., :double/gte).
   When adding support for new constraints, add entries here AND implement
   corresponding defmethod parse-constraint handlers below."
  #{;; Numeric constraints (uint32, int32, uint64, int64, double, float)
    :uint32/gt :uint32/gte :uint32/lt :uint32/lte :uint32/example
    :int32/gt :int32/gte :int32/lt :int32/lte :int32/example
    :uint64/gt :uint64/gte :uint64/lt :uint64/lte :uint64/example
    :int64/gt :int64/gte :int64/lt :int64/lte :int64/example
    :double/gt :double/gte :double/lt :double/lte :double/example
    :float/gt :float/gte :float/lt :float/lte :float/example

    ;; String constraints
    :string/minLen :string/maxLen :string/pattern :string/in :string/email

    ;; Bytes constraints
    :bytes/minLen :bytes/maxLen

    ;; Enum constraints
    :enum/definedOnly :enum/notIn

    ;; Repeated constraints
    :repeated/minItems :repeated/maxItems

    ;; General constraints
    :required})

(defmulti parse-constraint
  "Parse a single buf.validate constraint.
   Dispatches on constraint type as keyword :type/constraint.

   Args:
     type-key: The buf.validate type key (e.g., 'double', 'string')
     constraint-key: The constraint name (e.g., 'gte', 'minLen')
     value: The constraint value from JSON

   Returns:
     Map with constraint keyword and parsed value (e.g., {:gte 0.5})"
  (fn [type-key constraint-key _value]
    (if constraint-key
      (keyword (str type-key "/" constraint-key))
      (keyword type-key))))

;; Numeric constraint handlers (gt, gte, lt, lte, example)
(defmethod parse-constraint :uint32/gt [_ _ v] {:gt (parse-number v)})
(defmethod parse-constraint :uint32/gte [_ _ v] {:gte (parse-number v)})
(defmethod parse-constraint :uint32/lt [_ _ v] {:lt (parse-number v)})
(defmethod parse-constraint :uint32/lte [_ _ v] {:lte (parse-number v)})
(defmethod parse-constraint :uint32/example [_ _ v] {:example (mapv parse-number v)})

(defmethod parse-constraint :int32/gt [_ _ v] {:gt (parse-number v)})
(defmethod parse-constraint :int32/gte [_ _ v] {:gte (parse-number v)})
(defmethod parse-constraint :int32/lt [_ _ v] {:lt (parse-number v)})
(defmethod parse-constraint :int32/lte [_ _ v] {:lte (parse-number v)})
(defmethod parse-constraint :int32/example [_ _ v] {:example (mapv parse-number v)})

(defmethod parse-constraint :uint64/gt [_ _ v] {:gt (parse-number v)})
(defmethod parse-constraint :uint64/gte [_ _ v] {:gte (parse-number v)})
(defmethod parse-constraint :uint64/lt [_ _ v] {:lt (parse-number v)})
(defmethod parse-constraint :uint64/lte [_ _ v] {:lte (parse-number v)})
(defmethod parse-constraint :uint64/example [_ _ v] {:example (mapv parse-number v)})

(defmethod parse-constraint :int64/gt [_ _ v] {:gt (parse-number v)})
(defmethod parse-constraint :int64/gte [_ _ v] {:gte (parse-number v)})
(defmethod parse-constraint :int64/lt [_ _ v] {:lt (parse-number v)})
(defmethod parse-constraint :int64/lte [_ _ v] {:lte (parse-number v)})
(defmethod parse-constraint :int64/example [_ _ v] {:example (mapv parse-number v)})

(defmethod parse-constraint :double/gt [_ _ v] {:gt (parse-number v)})
(defmethod parse-constraint :double/gte [_ _ v] {:gte (parse-number v)})
(defmethod parse-constraint :double/lt [_ _ v] {:lt (parse-number v)})
(defmethod parse-constraint :double/lte [_ _ v] {:lte (parse-number v)})
(defmethod parse-constraint :double/example [_ _ v] {:example (mapv parse-number v)})

(defmethod parse-constraint :float/gt [_ _ v] {:gt (parse-number v)})
(defmethod parse-constraint :float/gte [_ _ v] {:gte (parse-number v)})
(defmethod parse-constraint :float/lt [_ _ v] {:lt (parse-number v)})
(defmethod parse-constraint :float/lte [_ _ v] {:lte (parse-number v)})
(defmethod parse-constraint :float/example [_ _ v] {:example (mapv parse-number v)})

;; String constraint handlers
(defmethod parse-constraint :string/minLen [_ _ v] {:min-len (parse-number v)})
(defmethod parse-constraint :string/maxLen [_ _ v] {:max-len (parse-number v)})
(defmethod parse-constraint :string/pattern [_ _ v] {:pattern v})
(defmethod parse-constraint :string/in [_ _ v] {:in (vec v)})
(defmethod parse-constraint :string/email [_ _ v] {:email (boolean v)})

;; Bytes constraint handlers
(defmethod parse-constraint :bytes/minLen [_ _ v] {:min-len (parse-number v)})
(defmethod parse-constraint :bytes/maxLen [_ _ v] {:max-len (parse-number v)})

;; Enum constraint handlers
(defmethod parse-constraint :enum/definedOnly [_ _ v] {:defined-only (boolean v)})
(defmethod parse-constraint :enum/notIn [_ _ v] {:not-in (vec v)})

;; Repeated constraint handlers
(defmethod parse-constraint :repeated/minItems [_ _ v] {:min-items (parse-number v)})
(defmethod parse-constraint :repeated/maxItems [_ _ v] {:max-items (parse-number v)})

;; Required constraint handler (special case - appears at top level of validate object)
(defmethod parse-constraint :required [_ _ v] {:required (boolean v)})

(defn- parse-buf-validate-constraints
  "Extract constraints from buf.validate.field options with exhaustive checking.
   Throws ex-info if an unknown constraint type is encountered."
  [options]
  (when-let [validate (get options "[buf.validate.field]")]
    (let [constraints (volatile! (transient {}))]

      ;; Process all type-level constraints (numeric, string, bytes, enum, repeated)
      (doseq [type-key (keys validate)
              :when (not= type-key "required") ;; Handle required separately below
              :let [rules (get validate type-key)]]
        ;; Process each constraint within this type
        (doseq [constraint-key (keys rules)]
          (let [dispatch-key (keyword (str type-key "/" constraint-key))
                value (get rules constraint-key)]

            ;; Check if handler exists in registry
            (when-not (contains? constraint-registry dispatch-key)
              (throw (ex-info
                      (str "Unknown buf.validate constraint: " dispatch-key "\n"
                           "  Available constraints in registry:\n"
                           "    " (str/join "\n    " (sort constraint-registry)) "\n\n"
                           "  To add support:\n"
                           "    1. Add " dispatch-key " to constraint-registry\n"
                           "    2. Add handler: (defmethod parse-constraint " dispatch-key " [_ _ v] ...)\n"
                           "    3. Update Constraints schema in schema.clj\n"
                           "    4. Update format-constraints in render.clj")
                      {:type :unknown-constraint
                       :constraint dispatch-key
                       :type-key type-key
                       :constraint-key constraint-key
                       :value value})))

            ;; Parse using multimethod and merge result
            (let [result (parse-constraint type-key constraint-key value)]
              (doseq [[k v] result]
                (vswap! constraints assoc! k v))))))

      ;; Handle 'required' constraint (top-level in validate object)
      (when (contains? validate "required")
        (let [result (parse-constraint "required" nil (get validate "required"))]
          (doseq [[k v] result]
            (vswap! constraints assoc! k v))))

      ;; Return persistent map if non-empty
      (let [result (persistent! @constraints)]
        (when (seq result)
          result)))))

(defn- parse-field
  "Parse a field descriptor into our schema format."
  [field-desc]
  (let [base {:number (get field-desc "number")
              :name (get field-desc "name")
              :type (get type-mapping (get field-desc "type") :unknown)}]
    (cond-> base
      ;; Add type reference for every type whose real type is NAMED
      (contains? types-with-type-ref (:type base))
      (assoc :type-ref (normalize-type-ref (get field-desc "typeName")))

      ;; Mark repeated fields
      (= "LABEL_REPEATED" (get field-desc "label"))
      (assoc :repeated true)

      ;; Add constraints if present
      (some? (parse-buf-validate-constraints (get field-desc "options")))
      (assoc :constraints (parse-buf-validate-constraints (get field-desc "options"))))))

(defn- parse-oneofs
  "Parse oneof declarations from a message."
  [msg-desc]
  (when-let [oneof-decls (seq (get msg-desc "oneofDecl"))]
    (let [fields (get msg-desc "field" [])
          ;; Group field numbers by oneof index
          field-by-oneof (group-by #(get % "oneofIndex") fields)]
      (vec
       (for [[idx oneof-decl] (map-indexed vector oneof-decls)]
         (let [oneof-fields (get field-by-oneof idx [])
               options (get oneof-decl "options" {})
               validate-opts (get options "[buf.validate.oneof]" {})]
           {:name (get oneof-decl "name")
            :required (boolean (get validate-opts "required" false))
            :fields (mapv #(get % "number") oneof-fields)}))))))

(defn- qualified-id
  "The fully-qualified id of a declaration: its package, the message path it is
   nested in, and its own name, joined by dots.

   ONE BUILDER FOR MESSAGES AND ENUMS. They are declared in the same scopes and
   are referenced through the same normalized dotted path, so two builders is
   two chances to disagree about one fact — and the enum half DID disagree: it
   concatenated with a literal dot, so a file declaring no package produced an
   id with a LEADING dot, which no `:type-ref` can ever match because
   `normalize-type-ref` strips exactly that dot. Dropping the blank segments is
   what makes the two answers the same answer."
  [package parent-path nm]
  (str/join "." (remove str/blank? [package parent-path nm])))

(defn- declaring-scope
  "The package a declaration nested at `parent-path` records — the scope it is
   declared in, which for a nested declaration is its enclosing message rather
   than the file package."
  [package parent-path]
  (qualified-id package parent-path nil))

(defn- parse-enum-value
  "Parse an enum value descriptor."
  [value-desc]
  {:number (get value-desc "number")
   :name (get value-desc "name")})

(defn- parse-enum
  "Parse an enum descriptor declared at `parent-path` inside `package` (nil
   `parent-path` for a file-level enum)."
  [enum-desc package source parent-path]
  {:id (qualified-id package parent-path (get enum-desc "name"))
   :name (get enum-desc "name")
   :package (declaring-scope package parent-path)
   :source source
   :values (mapv parse-enum-value (get enum-desc "value" []))})

(defn- parse-message
  "Parse a message descriptor recursively, returning `{:messages [...] :enums
   [...]}` — this message and everything declared INSIDE it, at any depth.

   ENUMS COME BACK FROM HERE, not from the file level alone. Message parsing
   always recursed; enum parsing was applied to the file's own `enumType` only,
   so an enum declared inside a message was dropped while every field naming it
   survived — a reference resolving to nothing, which a consumer can only
   refuse. Following the same recursion is what makes the two halves agree."
  [msg-desc package source parent-path]
  (let [nm (get msg-desc "name")
        current-path (if parent-path
                       (str parent-path "." nm)
                       nm)

        ;; Parse this message
        message {:id (qualified-id package parent-path nm)
                 :name nm
                 :package (declaring-scope package parent-path)
                 :source source
                 ;; Include all fields (oneof fields are referenced by number in oneofs)
                 :fields (mapv parse-field (get msg-desc "field" []))}

        message (if-let [oneofs (parse-oneofs msg-desc)]
                  (assoc message :oneofs (vec oneofs))
                  message)

        ;; Enums declared in THIS message's scope
        own-enums (map #(parse-enum % package source current-path)
                       (get msg-desc "enumType" []))

        ;; Parse nested messages
        nested (for [child (get msg-desc "nestedType" [])
                     ;; Skip map entry types
                     :when (not (get-in child ["options" "mapEntry"]))]
                 (parse-message child package source current-path))]

    {:messages (cons message (mapcat :messages nested))
     :enums (concat own-enums (mapcat :enums nested))}))

(defn- parse-file
  "Parse a single proto file from the descriptor set."
  [file-desc]
  (let [source (get file-desc "name")
        package (get file-desc "package" "")

        ;; Messages, recursively, each carrying the enums declared inside it
        parsed (map #(parse-message % package source nil)
                    (get file-desc "messageType" []))

        ;; File-level enums, plus every enum a message declared
        enums (concat (map #(parse-enum % package source nil)
                           (get file-desc "enumType" []))
                      (mapcat :enums parsed))]

    {:messages (mapcat :messages parsed)
     :enums enums}))

(defn- filter-project-files
  "Filter to jon_shared_* and opaque/* proto files."
  [files]
  (filter (fn [file]
            (let [nm (get file "name" "")]
              (or (str/starts-with? nm "jon_shared_")
                  (str/starts-with? nm "opaque/"))))
          files))

(defn parse-descriptor-file
  "Parse a descriptor-set.json file into proto-db format."
  [path]
  (t/log! :info ["Parsing" path])
  (let [content (json/read-str (slurp (io/file path)))
        files (filter-project-files (get content "file" []))

        _ (t/log! :debug ["Found" (count files) "project proto files"])

        results (map parse-file files)

        messages (->> results
                      (mapcat :messages)
                      (map (juxt :id identity))
                      (into {}))

        enums (->> results
                   (mapcat :enums)
                   (map (juxt :id identity))
                   (into {}))]

    (t/log! :info ["Parsed" (count messages) "messages," (count enums) "enums"])

    {:messages messages
     :enums enums
     :search-index {}}))

(defn- tokenize
  "Tokenize a string into searchable terms."
  [s]
  (when s
    (->> (str/split (str/lower-case s) #"[^a-z0-9]+")
         (remove str/blank?)
         (into #{}))))

(defn build-search-index
  "Build pre-computed search index from messages and enums."
  [db]
  (let [index (atom {})]
    ;; Index messages
    (doseq [[id msg] (:messages db)]
      (let [terms (into #{}
                        (concat
                         (tokenize (:name msg))
                         (tokenize (:package msg))
                         (tokenize (:description msg))
                         (mapcat #(tokenize (:name %)) (:fields msg))))]
        (doseq [term terms]
          (swap! index update term (fnil conj []) id))))

    ;; Index enums
    (doseq [[id enum] (:enums db)]
      (let [terms (into #{}
                        (concat
                         (tokenize (:name enum))
                         (tokenize (:package enum))
                         (tokenize (:description enum))
                         (mapcat #(tokenize (:name %)) (:values enum))))]
        (doseq [term terms]
          (swap! index update term (fnil conj []) id))))

    (assoc db :search-index @index)))
