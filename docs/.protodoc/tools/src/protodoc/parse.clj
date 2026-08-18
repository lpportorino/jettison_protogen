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

(def element-rule-sets
  "The buf.validate rule sets that may judge ONE VALUE, spelled the way the
   descriptor spells them.

   A repeated field's `repeated.items` carries a whole `FieldRules` judging each
   ELEMENT rather than the list, and this set is what may legally appear inside
   it. It is the descriptor's scalar and enum types and nothing else, and the
   two absences are load-bearing for different reasons:

   `repeated` and `map` are absent because an element of a list is never itself
   a list or a map — and they are the pair whose omission actually bites, since
   their constraint keys (`minItems`, `maxItems`) ARE registered. Admitting the
   set name would let `items: {repeated: {max_items: 4}}` parse into a rule this
   database cannot express and no consumer can emit, which is the silent
   approximation this file refuses everywhere else.

   `message` and `group` are absent because protovalidate declares no scalar
   rule set for either, so an element of one carries nothing to record.

   `protodoc.schema/ElementRuleSet` mirrors this set as keywords and
   `protodoc.schema-test` asserts the two agree — a comment claiming they do
   would not have caught the day they stopped."
  #{"double" "float" "int32" "int64" "uint32" "uint64"
    "sint32" "sint64" "fixed32" "fixed64" "sfixed32" "sfixed64"
    "bool" "string" "bytes" "enum"})

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

    ;; Repeated constraints — the list itself, and its ELEMENTS
    :repeated/minItems :repeated/maxItems :repeated/items

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

;; Repeated constraint handlers — the LIST's own bounds
(defmethod parse-constraint :repeated/minItems [_ _ v] {:min-items (parse-number v)})
(defmethod parse-constraint :repeated/maxItems [_ _ v] {:max-items (parse-number v)})

;; Required constraint handler (special case - appears at top level of validate object)
(defmethod parse-constraint :required [_ _ v] {:required (boolean v)})

(defn- unknown-constraint!
  "Throw the refusal an unregistered dispatch key earns, naming the remedy at
   every layer that has to learn a new constraint.

   `path` is where the constraint was found: empty at the field's own rules,
   `[:items]` inside a repeated field's element rules. It rides in the message
   as well as the data because a reader who sees only the dispatch key will
   look for the constraint at the top level and not find it."
  [dispatch-key type-key constraint-key value path]
  (throw (ex-info
          (str "Unknown buf.validate constraint: " dispatch-key
               (when (seq path)
                 (str " (inside repeated." (str/join "." (map name path)) ")"))
               "\n"
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
           :value value
           :path path})))

(defn- parse-rule-set
  "Parse one type-scoped rules block — the `{constraint-key value}` map that
   sits under `type-key` — into the flat constraint map this database records.

   EXHAUSTIVE: a dispatch key the registry does not carry is a refusal, never a
   drop. A dropped constraint leaves a database claiming a field is less
   constrained than its source says, and nothing downstream can tell that from
   a field that was never constrained at all."
  [type-key rules path]
  (reduce-kv
   (fn [acc constraint-key value]
     (let [dispatch-key (keyword (str type-key "/" constraint-key))]
       (when-not (contains? constraint-registry dispatch-key)
         (unknown-constraint! dispatch-key type-key constraint-key value path))
       (merge acc (parse-constraint type-key constraint-key value))))
   {} rules))

(defn- parse-element-rules
  "Parse a repeated field's `items` block — one protovalidate `FieldRules`
   judging each ELEMENT rather than the list — into `{rule-set constraints}`.

   THE RULE SET IS KEPT, NOT FOLDED ONTO THE FIELD'S OWN TYPE. Recording only
   the element constraints would leave a database that cannot tell a truthful
   re-emission from a silent substitution: protoc compiles `repeated string x =
   1 [(buf.validate.field).repeated = {items: {int32: {gte: 1}}}]` without
   complaint, and a consumer re-spelling those rules as the field's own type
   would emit a schema the source never declared. Keeping the name is what lets
   the consumer refuse it knowingly.

   A key that is not an element rule set is refused rather than dropped —
   including `repeated`, whose constraint keys are registered and which would
   therefore parse into a rule nothing here can express (see
   `element-rule-sets`)."
  [items]
  (reduce-kv
   (fn [acc rule-set rules]
     (when-not (contains? element-rule-sets rule-set)
       (throw (ex-info
               (str "Unknown buf.validate element rule set: " rule-set "\n"
                    "  A repeated field's items block carries the rules that judge ONE\n"
                    "  element. The rule sets that can do that are:\n"
                    "    " (str/join ", " (sort element-rule-sets)) "\n\n"
                    "  `repeated` and `map` are absent because an element is never itself a\n"
                    "  list or a map; `message` and `group` have no protovalidate rule set.")
               {:type :unknown-element-rule-set
                :rule-set rule-set
                :value rules
                :path [:items]})))
     (assoc acc (keyword rule-set) (parse-rule-set rule-set rules [:items])))
   {} items))

;; Element-level repeated handler — the nested FieldRules judging each item
(defmethod parse-constraint :repeated/items [_ _ v] {:items (parse-element-rules v)})

(defn- parse-buf-validate-constraints
  "Extract constraints from buf.validate.field options with exhaustive checking.
   Throws ex-info if an unknown constraint type is encountered.

   TWO SHAPES SIT SIDE BY SIDE IN `FieldRules` and both are handled here rather
   than by a special case downstream: a type-scoped RULE SET, whose value is a
   map of constraint keys, and a DIRECT key such as `required`, whose value is a
   scalar. Anything else — `ignore`, `cel` — is refused by the registry check
   rather than dropped, which is what the map/non-map split buys: it used to
   throw a ClassCastException on a non-map value, which named neither the key
   nor the remedy."
  [options]
  (when-let [validate (get options "[buf.validate.field]")]
    (let [result (reduce-kv
                  (fn [acc k v]
                    (if (map? v)
                      (merge acc (parse-rule-set k v []))
                      (let [dispatch-key (keyword k)]
                        (when-not (contains? constraint-registry dispatch-key)
                          (unknown-constraint! dispatch-key k nil v []))
                        (merge acc (parse-constraint k nil v)))))
                  {} validate)]
      (when (seq result)
        result))))

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
