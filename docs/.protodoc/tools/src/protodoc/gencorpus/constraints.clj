(ns protodoc.gencorpus.constraints
  "Descriptor-sourced constraints — the drift-eliminating fix (the plan's
   \"v2: read buf.validate from the binpb\"). proto-db is a DOCUMENTATION
   projection that drifts from the live `.proto` in BOTH directions — it carries
   phantom bounds the wire lacks (ser-side altitude) AND misses bounds the wire
   enforces (`repeated.min_items` 64-vs-160, the `required` flags). The oracle
   reads the LIVE buf.validate rules embedded in the descriptor; so must the
   generator, or it emits values the oracle silently filters.

   This re-parses the committed FileDescriptorSet WITH the buf.validate extension
   registry and extracts each field's `FieldRules` into the proto-db-shaped
   constraints map `constraints->malli` already speaks (`:gte/:gt/:lte/:lt`,
   `:min-len/:max-len`, `:min-items/:max-items`, `:pattern`, `:in`, `:not-in`,
   `:defined-only`, `:required`). `effective-db` produces a db whose `:constraints`
   are these live rules (merged with proto-db's `:example` domain seeds), keyed by
   message full-name — a drop-in for the assembler/parity/orchestrator, covering
   EVERY message in the pool (including the ones proto-db never had)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.google.protobuf
            DescriptorProtos$DescriptorProto
            DescriptorProtos$FieldDescriptorProto
            DescriptorProtos$FieldOptions
            DescriptorProtos$FileDescriptorProto
            DescriptorProtos$FileDescriptorSet
            Descriptors$FieldDescriptor
            ExtensionRegistry
            Message]))

(set! *warn-on-reflection* true)

;; The rule keys constraints->malli understands; anything else (const, example,
;; finite, cel, …) is ignored — we only surface the modelled constraint surface.
(def ^:private rule-keys
  #{:gte :gt :lte :lt :min-len :max-len :min-items :max-items
    :pattern :in :not-in :defined-only})

(defn- buf-validate-registry
  "An ExtensionRegistry with the buf.validate extensions registered, so a
   re-parse resolves `[buf.validate.field]` instead of dropping it to an unknown
   field."
  ^ExtensionRegistry []
  (let [reg (ExtensionRegistry/newInstance)]
    (clojure.lang.Reflector/invokeStaticMethod
     "build.buf.validate.ValidateProto" "registerAllExtensions" (object-array [reg]))
    reg))

(defn- field-ext
  "The buf.validate `[buf.validate.field]` extension field object
   (`ValidateProto/field`), fetched via reflection since it is not imported —
   `extract` passes it to `hasExtension`/`getExtension` to read each field's
   FieldRules off its FieldOptions."
  []
  (clojure.lang.Reflector/getStaticField "build.buf.validate.ValidateProto" "field"))

(defn- rule->kw
  "A buf.validate rule sub-message field's snake_case wire name as the
   proto-db-shaped keyword `submsg->constraints` keys the constraints map with
   (`min_len` → `:min-len`)."
  [^Descriptors$FieldDescriptor fd]
  (keyword (str/replace (Descriptors$FieldDescriptor/.getName fd) "_" "-")))

(defn- submsg->constraints
  "The set rule fields of a numeric/string/repeated/enum rule sub-message →
   {kw value}, whitelisted to the modelled keys."
  [^Message sub]
  (into {} (for [[^Descriptors$FieldDescriptor fd v] (Message/.getAllFields sub)
                 :let [k (rule->kw fd)]
                 :when (rule-keys k)]
             [k (cond (instance? java.util.List v) (vec v) :else v)])))

(defn- field-rules->constraints
  "A FieldRules message → the proto-db-shaped constraints map (flat): the set
   numeric/string/repeated/enum sub-rule merged with the top-level `:required`."
  [^Message fr]
  (reduce (fn [acc [^Descriptors$FieldDescriptor fd v]]
            (cond
              (= "required" (Descriptors$FieldDescriptor/.getName fd)) (assoc acc :required v)
              (instance? Message v) (merge acc (submsg->constraints v))
              :else acc))
          {} (Message/.getAllFields fr)))

(defn- proto-msgs
  "Seq of [full-name FieldDescriptorProto-seq] for every message (nested
   included) in a registry-parsed FileDescriptorProto, full-name = package +
   nested path (matching Descriptor/getFullName)."
  [^DescriptorProtos$FileDescriptorProto fp]
  (let [pkg (DescriptorProtos$FileDescriptorProto/.getPackage fp)]
    (letfn [(walk [prefix ^DescriptorProtos$DescriptorProto dp]
              (let [fqn (str prefix (DescriptorProtos$DescriptorProto/.getName dp))]
                (cons [fqn (DescriptorProtos$DescriptorProto/.getFieldList dp)]
                      (mapcat #(walk (str fqn ".") %)
                              (DescriptorProtos$DescriptorProto/.getNestedTypeList dp)))))]
      (mapcat #(walk (if (str/blank? pkg) "" (str pkg ".")) %)
              (DescriptorProtos$FileDescriptorProto/.getMessageTypeList fp)))))

(defn extract
  "Parse `binpb-path` with the buf.validate registry and return
   `{message-full-name → {field-name → constraints-map}}` — the LIVE rules. A
   field with no buf.validate rule has no entry (no constraints)."
  [binpb-path]
  (let [reg (buf-validate-registry)
        ;; hint as ExtensionLite so the FieldOptions hasExtension/getExtension
        ;; overloads resolve without reflection (field-ext returns an untyped
        ;; Object from getStaticField).
        ^com.google.protobuf.ExtensionLite ext (field-ext)
        fdset (with-open [in (io/input-stream (io/file binpb-path))]
                (DescriptorProtos$FileDescriptorSet/parseFrom in reg))]
    (into {}
          (for [^DescriptorProtos$FileDescriptorProto fp (DescriptorProtos$FileDescriptorSet/.getFileList fdset)
                [fqn fields] (proto-msgs fp)]
            [fqn (into {}
                       (for [^DescriptorProtos$FieldDescriptorProto f fields
                             :let [^DescriptorProtos$FieldOptions opts (DescriptorProtos$FieldDescriptorProto/.getOptions f)]
                             :when (DescriptorProtos$FieldOptions/.hasExtension opts ext)
                             :let [c (field-rules->constraints (DescriptorProtos$FieldOptions/.getExtension opts ext))]
                             :when (seq c)]
                         [(DescriptorProtos$FieldDescriptorProto/.getName f) c]))]))))

(defn- proto-db-examples
  "{message-full-name → {field-name → :example-vector}} from proto-db (the only
   source of the domain example seeds — they are doc data, not wire rules)."
  [proto-db]
  (into {} (for [[id m] (:messages proto-db)]
             [id (into {} (for [f (:fields m) :when (:example (:constraints f))]
                            [(:name f) (:example (:constraints f))]))])))

(defn effective-db
  "A db whose field `:constraints` are the LIVE descriptor rules (merged with
   proto-db `:example` seeds), covering EVERY message the pool resolves — a
   drop-in for `db-field-constraints`. `:enums` is carried from proto-db (used by
   the parity verdict for enum names). `pool` is the descriptor pool (structure /
   field names); `binpb-path` is re-parsed for the rules."
  [pool binpb-path proto-db-path]
  (let [proto-db (edn/read-string (slurp proto-db-path))
        live (extract binpb-path)
        examples (proto-db-examples proto-db)]
    {:enums (:enums proto-db)
     :messages
     (into {}
           (for [[full-name ^com.google.protobuf.Descriptors$Descriptor d] pool]
             [full-name
              {:id full-name
               :fields (vec (for [^Descriptors$FieldDescriptor f (com.google.protobuf.Descriptors$Descriptor/.getFields d)
                                  :let [fname (Descriptors$FieldDescriptor/.getName f)
                                        cs (get-in live [full-name fname])
                                        ex (get-in examples [full-name fname])]]
                              {:name fname
                               :constraints (cond-> (or cs {})
                                              ex (assoc :example ex))}))}]))}))
