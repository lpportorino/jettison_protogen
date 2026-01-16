(ns protodoc.parse
  "Parse protoc JSON descriptor format into proto-db EDN."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

;; Type mapping from proto type strings to keywords
(def type-mapping
  {"TYPE_DOUBLE"   :double
   "TYPE_FLOAT"    :float
   "TYPE_INT64"    :int64
   "TYPE_UINT64"   :uint64
   "TYPE_INT32"    :int32
   "TYPE_UINT32"   :uint32
   "TYPE_FIXED64"  :uint64
   "TYPE_FIXED32"  :uint32
   "TYPE_SFIXED32" :int32
   "TYPE_SFIXED64" :int64
   "TYPE_SINT32"   :int32
   "TYPE_SINT64"   :int64
   "TYPE_BOOL"     :bool
   "TYPE_STRING"   :string
   "TYPE_BYTES"    :bytes
   "TYPE_ENUM"     :enum
   "TYPE_MESSAGE"  :message
   "TYPE_GROUP"    :message})

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
    :repeated/minItems

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

;; Required constraint handler (special case - appears at top level of validate object)
(defmethod parse-constraint :required [_ _ v] {:required (boolean v)})

(defn- parse-buf-validate-constraints
  "Extract constraints from buf.validate.field options with exhaustive checking.
   Throws ex-info if an unknown constraint type is encountered."
  [options]
  (when-let [validate (get options "[buf.validate.field]")]
    (let [constraints (transient {})]

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
                (assoc! constraints k v))))))

      ;; Handle 'required' constraint (top-level in validate object)
      (when (contains? validate "required")
        (let [result (parse-constraint "required" nil (get validate "required"))]
          (doseq [[k v] result]
            (assoc! constraints k v))))

      ;; Return persistent map if non-empty
      (let [result (persistent! constraints)]
        (when (seq result)
          result)))))

(defn- parse-field
  "Parse a field descriptor into our schema format."
  [field-desc]
  (let [base {:number (get field-desc "number")
              :name (get field-desc "name")
              :type (get type-mapping (get field-desc "type") :unknown)}]
    (cond-> base
      ;; Add type reference for enums and messages
      (contains? #{:enum :message} (:type base))
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

(defn- build-message-id
  "Build full message ID from package and nested path."
  [package parent-path name]
  (let [path-parts (if parent-path
                     [package parent-path name]
                     [package name])]
    (str/join "." (remove str/blank? path-parts))))

(defn- parse-message
  "Parse a message descriptor recursively, handling nested types."
  [msg-desc package source parent-path]
  (let [name (get msg-desc "name")
        current-path (if parent-path
                       (str parent-path "." name)
                       name)
        id (build-message-id package parent-path name)

        ;; Parse this message
        message {:id id
                 :name name
                 :package (if parent-path
                            (str package "." parent-path)
                            package)
                 :source source
                 ;; Include all fields (oneof fields are referenced by number in oneofs)
                 :fields (mapv parse-field (get msg-desc "field" []))}

        message (if-let [oneofs (parse-oneofs msg-desc)]
                  (assoc message :oneofs (vec oneofs))
                  message)

        ;; Parse nested messages
        nested-msgs (for [nested (get msg-desc "nestedType" [])
                         ;; Skip map entry types
                         :when (not (get-in nested ["options" "mapEntry"]))]
                      (parse-message nested package source current-path))]

    (cons message (mapcat identity nested-msgs))))

(defn- parse-enum-value
  "Parse an enum value descriptor."
  [value-desc]
  {:number (get value-desc "number")
   :name (get value-desc "name")})

(defn- parse-enum
  "Parse an enum descriptor."
  [enum-desc package source]
  {:id (str package "." (get enum-desc "name"))
   :name (get enum-desc "name")
   :package package
   :source source
   :values (mapv parse-enum-value (get enum-desc "value" []))})

(defn- parse-file
  "Parse a single proto file from the descriptor set."
  [file-desc]
  (let [source (get file-desc "name")
        package (get file-desc "package" "")

        ;; Parse top-level messages (recursively handles nested types)
        messages (mapcat #(parse-message % package source nil)
                         (get file-desc "messageType" []))

        ;; Parse enums (top-level only, nested are handled with their parent)
        enums (map #(parse-enum % package source)
                   (get file-desc "enumType" []))]

    {:messages messages
     :enums enums}))

(defn- filter-jon-files
  "Filter to only jon_shared_* proto files."
  [files]
  (filter #(str/starts-with? (get % "name" "") "jon_shared_") files))

(defn parse-descriptor-file
  "Parse a descriptor-set.json file into proto-db format."
  [path]
  (t/log! :info ["Parsing" path])
  (let [content (json/read-str (slurp (io/file path)))
        files (filter-jon-files (get content "file" []))

        _ (t/log! :debug ["Found" (count files) "jon_shared proto files"])

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
