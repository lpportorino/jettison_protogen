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

(defn- parse-buf-validate-constraints
  "Extract constraints from buf.validate.field options."
  [options]
  (when-let [validate (get options "[buf.validate.field]")]
    (let [constraints (atom {})]
      ;; Numeric constraints (uint32, int32, uint64, int64, double, float)
      (doseq [type-key ["uint32" "int32" "uint64" "int64" "double" "float"]]
        (when-let [rules (get validate type-key)]
          (when-let [v (get rules "gt")]  (swap! constraints assoc :gt (parse-number v)))
          (when-let [v (get rules "gte")] (swap! constraints assoc :gte (parse-number v)))
          (when-let [v (get rules "lt")]  (swap! constraints assoc :lt (parse-number v)))
          (when-let [v (get rules "lte")] (swap! constraints assoc :lte (parse-number v)))
          (when-let [v (get rules "example")]
            (swap! constraints assoc :example (mapv parse-number v)))))

      ;; String constraints
      (when-let [rules (get validate "string")]
        (when-let [v (get rules "minLen")] (swap! constraints assoc :min-len v))
        (when-let [v (get rules "maxLen")] (swap! constraints assoc :max-len v))
        (when-let [v (get rules "pattern")] (swap! constraints assoc :pattern v)))

      ;; Bytes constraints
      (when-let [rules (get validate "bytes")]
        (when-let [v (get rules "minLen")] (swap! constraints assoc :min-len v))
        (when-let [v (get rules "maxLen")] (swap! constraints assoc :max-len v)))

      ;; Enum constraints
      (when-let [rules (get validate "enum")]
        (when (get rules "definedOnly")
          (swap! constraints assoc :defined-only true))
        (when-let [v (get rules "notIn")]
          (swap! constraints assoc :not-in (vec v))))

      ;; Required constraint
      (when (get validate "required")
        (swap! constraints assoc :required true))

      (when (seq @constraints)
        @constraints))))

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
