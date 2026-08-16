(ns protocol-gen.constraints
  "Validation constraints, carried from the descriptor database back out as
   field options on the projected schema.

   WHY THIS IS NOT OPTIONAL POLISH. A constraint declared once at the source
   and dropped by a projection produces a schema that is WEAKER than the one it
   claims to project, and nothing about the emitted file says so. A group
   generated from it would accept a value the source forbids, and the first
   thing that notices is the peer that rejects the message.

   WHAT MUST SURVIVE, and it is a stronger requirement than \"emit an
   annotation\". A descriptor-driven consumer reads the DESCRIPTOR, not the
   text, and a generation leg that strips extensions or forgets the import
   produces a descriptor with no rules in it at all. So the property that
   matters is that a constraint reaches the compiled descriptor, and the canary
   compiles the emitted text with protoc and reads the numbers and the rules
   back out of the resulting descriptor set rather than out of the text.

   CLOSED BY KEY, HERE AND NOT AT LOAD. The database schema tolerates an
   unfamiliar constraint key so that a message nobody projected cannot kill the
   run. This namespace refuses one on a GRANTED field, because that is exactly
   where dropping it would weaken an emitted schema.

   THE RULE SET IS CHOSEN BY THE FIELD'S TYPE, not by the constraint. `min_len`
   belongs to `string` rules on a string and to `bytes` rules on bytes, and
   `min_items` belongs to `repeated` rules whatever the element is — so a
   constraint that cannot apply to a field's type is a refusal rather than
   something to emit into the nearest plausible rule set."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [protocol-gen.constructs :as constructs]
            [protocol-gen.db :as db]))

(set! *warn-on-reflection* true)

(def numeric-types
  "Field types carrying numeric bounds."
  #{:double :float :int32 :int64 :uint32 :uint64})

(def rules-name
  "Field type -> the protovalidate rule set that type's constraints live in.

   `:message` is absent deliberately: protovalidate has no scalar rule set for
   a message field, so every type-scoped constraint on one is a mismatch rather
   than something to route somewhere."
  {:double "double" :float "float" :int32 "int32" :int64 "int64"
   :uint32 "uint32" :uint64 "uint64" :bool "bool" :string "string"
   :bytes "bytes" :enum "enum"})

(def constraint-table
  "Database constraint key -> how it is emitted.

   `:spelling` is the protovalidate field name — snake_case, matching the
   spelling this repository's own protos already use. `:applies` is either a
   set of field types, `:repeated` (the rule set that judges the LIST rather
   than the element), or `:top-level` (an option on the field itself rather
   than inside a rule set). `:vector?` marks a repeated rule value."
  {:gt {:spelling "gt" :applies numeric-types}
   :gte {:spelling "gte" :applies numeric-types}
   :lt {:spelling "lt" :applies numeric-types}
   :lte {:spelling "lte" :applies numeric-types}
   :example {:spelling "example" :applies numeric-types :vector? true}
   :min-len {:spelling "min_len" :applies #{:string :bytes}}
   :max-len {:spelling "max_len" :applies #{:string :bytes}}
   :pattern {:spelling "pattern" :applies #{:string}}
   :in {:spelling "in" :applies #{:string} :vector? true}
   :email {:spelling "email" :applies #{:string}}
   :defined-only {:spelling "defined_only" :applies #{:enum}}
   :not-in {:spelling "not_in" :applies #{:enum} :vector? true}
   :min-items {:spelling "min_items" :applies :repeated}
   :max-items {:spelling "max_items" :applies :repeated}
   :required {:spelling "required" :applies :top-level}})

(defn- literal
  "One constraint value as proto text-format. A vector becomes a bracketed
   list, a string is quoted, everything else prints as itself."
  [v]
  (cond
    (vector? v) (str "[" (str/join ", " (map literal v)) "]")
    (string? v) (pr-str v)
    :else (pr-str v)))

(defn- bucket-of
  "Which emitted rule set constraint `k` lands in for `fld` — a rule-set name,
   or a refusal when it cannot land anywhere."
  [subject fld k]
  (let [{:keys [applies]} (or (get constraint-table k)
                              (constructs/refuse! :unknown-constraint subject
                                                  (str "constraint " k " has no emission; the "
                                                       "known set is "
                                                       (pr-str (vec (sort (keys constraint-table)))))))]
    (cond
      (= :top-level applies) :top-level

      (= :repeated applies)
      (if (:repeated fld)
        "repeated"
        (constructs/refuse! :constraint-type-mismatch subject
                            (str "constraint " k " judges a list and this field is not repeated")))

      (contains? applies (:type fld))
      (or (get rules-name (:type fld))
          (constructs/refuse! :constraint-type-mismatch subject
                              (str "field type " (:type fld) " has no protovalidate rule set")))

      :else
      (constructs/refuse! :constraint-type-mismatch subject
                          (str "constraint " k " does not apply to a " (:type fld) " field; it "
                               "applies to " (pr-str (vec (sort applies))))))))

(defn field-options
  "The option strings for one projected field, sorted so two runs over the same
   input emit byte-identical text.

   `msg-id` names the message only so a refusal can say which field it is
   about; nothing about it reaches the emitted text."
  [msg-id fld]
  (let [subject (str msg-id "." (:name fld))
        buckets (group-by (fn [[k _]] (bucket-of subject fld k)) (:constraints fld))]
    (vec
     (sort
      (for [[bucket entries] buckets]
        (if (= :top-level bucket)
          (str/join ", "
                    (for [[k v] (sort-by (comp :spelling constraint-table key) entries)]
                      (str "(buf.validate.field)." (:spelling (constraint-table k))
                           " = " (literal v))))
          (str "(buf.validate.field)." bucket " = {"
               (str/join ", "
                         (for [[k v] (sort-by (comp :spelling constraint-table key) entries)]
                           (str (:spelling (constraint-table k)) ": " (literal v))))
               "}")))))))

(m/=> field-options
      [:=> [:cat [:string {:min 1}] db/field] [:vector [:string {:min 1}]]])

(def validate-import
  "The import an emitted file needs when any of its fields carries an option.

   NAMED HERE rather than in the emitter because it is a fact about THESE
   annotations: a file that emits no constraint must not import it, and a
   descriptor-driven consumer reading a file that forgot it gets no rules at
   all rather than an error it would notice."
  "buf/validate/validate.proto")

(defn imports-for
  "The imports an emitted file needs, given whether any field carried options.

   A function rather than a constant so the caller cannot import unconditionally
   — an unused import is not an error protoc reports, so nothing would catch it."
  [any-options?]
  (if any-options? [validate-import] []))

(m/=> imports-for [:=> [:cat :boolean] [:vector [:string {:min 1}]]])
