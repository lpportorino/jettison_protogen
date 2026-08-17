(ns protocol-gen.wire-encoding-test
  "The integer types whose WIRE ENCODING differs from the varint
   two's-complement family, carried end to end.

   WHY THIS IS ITS OWN NAMESPACE. The property is not about any one pass: it is
   that a field carrying one of these types is accepted by the load-time floor,
   survives the refusal pass, keeps its type through the projection, and reaches
   the emitted text spelled as ITSELF. A defect anywhere on that path produces
   the same visible outcome — a schema that compiles and decodes the same bytes
   to a different value — so the assertion has to span the passes rather than
   sit inside one of them.

   The producing parser used to fold `sint32`/`sint64` onto `int32`/`int64` and
   the `fixed`/`sfixed` family onto `uint32`/`uint64`/`int32`/`int64`, which
   `protocol-gen.constructs` named in its source as a class no check here could
   ever detect. It no longer does, so these types now REACH this generator and
   it must be able to say what they are."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protocol-gen.constraints :as constraints]
            [protocol-gen.constructs :as constructs]
            [protocol-gen.db :as db]
            [protocol-gen.emit :as emit]
            [protocol-gen.projection :as projection]
            [protocol-gen.render :as render]))

(def ^:private distinctly-encoded-integers
  "The six integer types that are NOT varint two's-complement: `sint*` is
   zigzag, `fixed*`/`sfixed*` are fixed-width. Spelled once here and every
   assertion below is driven from it, so a type added to the vocabulary is
   added in one place."
  [:sint32 :sint64 :fixed32 :fixed64 :sfixed32 :sfixed64])

(def ^:private database
  "One message carrying one field of each type, named for its own type so the
   emitted line is checkable without a second lookup."
  {:messages
   {"p.M" {:id "p.M" :name "M"
           :fields (vec (map-indexed
                         (fn [i t] {:number (inc i) :name (name t) :type t})
                         distinctly-encoded-integers))}}
   :enums {}})

(def ^:private policy-group
  {:id :g :package "p.g"
   :grants [{:message "p.M" :access #{:read} :fields :all}]})

(deftest every-distinct-encoding-is-a-type-this-generator-can-emit
  (doseq [t distinctly-encoded-integers]
    (testing (str t)
      (is (contains? db/scalar-types t)
          "a scalar the generator cannot name is refused as :unknown-field-type")
      (is (contains? db/known-types t))
      (is (not (contains? db/referring-types t))
          "these name no type; they ARE the type, so they carry no :type-ref"))))

(deftest every-distinct-encoding-has-its-own-protovalidate-rule-set
  ;; The rule-set name is the type's own spelling — verified against
  ;; buf.validate's FieldRules oneof, which offers one arm per descriptor type.
  ;; Routing a bound into the WRONG rule set is not a compile error: protoc
  ;; accepts it and the runtime then judges the value under another type's
  ;; rules.
  (doseq [t distinctly-encoded-integers]
    (testing (str t)
      (is (contains? constraints/numeric-types t))
      (is (= (name t) (get constraints/rules-name t))))))

(deftest bounds-on-a-distinctly-encoded-integer-land-in-its-own-rule-set
  (doseq [t distinctly-encoded-integers]
    (testing (str t)
      (is (= [(str "(buf.validate.field)." (name t) " = {gte: 1, lte: 9}")]
             (constraints/field-options
              "p.M" {:number 1 :name "a" :type t :constraints {:gte 1 :lte 9}}))))))

(deftest a-distinctly-encoded-integer-survives-projection-and-emission
  ;; THE END-TO-END CLAIM. Every pass runs: the projection resolves the grant
  ;; and stamps the number, the renderer turns the type into the emitted
  ;; spelling, and the emitter writes the line.
  (let [projected (projection/project-group database {} policy-group)
        text (emit/file->proto
              (render/render-group projected (fn [_ _] []) []))]
    (doseq [[i t] (map-indexed vector distinctly-encoded-integers)]
      (testing (str t)
        (is (str/includes? text (str "  " (name t) " " (name t) " = " (inc i) ";"))
            (str "the emitted text does not declare a " (name t) " field; got:\n" text))))
    (testing "and no field was emitted under a type it does not have"
      ;; A fold would have written `int32`/`int64`/`uint32`/`uint64` here.
      ;; Naming them explicitly is what makes this an absence probe with a
      ;; control rather than a restatement of the loop above.
      (doseq [folded ["int32" "int64" "uint32" "uint64"]]
        (is (not (str/includes? text (str " " folded " ")))
            (str "the emitted text names " folded ", which no field declares"))))))

(deftest a-proto2-group-is-refused-rather-than-emitted-as-a-message
  ;; proto3 has NO group syntax, so a group has no truthful emission here and
  ;; the honest outcome is a refusal. The producing parser records it as
  ;; `:group` with its `:type-ref` intact precisely so this refusal is
  ;; reachable — folded onto `:message` it would have been emitted as an
  ;; ordinary length-delimited field instead.
  ;;
  ;; Taken on the REFUSAL pass rather than through the projection: a group's
  ;; reference also names a type no policy granted, so on the generation path
  ;; the closure check would refuse the same input and the red would not be
  ;; attributable to this clause.
  (let [with-group {:messages {"p.M" {:id "p.M" :name "M"
                                      :fields [{:number 1 :name "leg" :type :group
                                                :type-ref "p.M.Leg"}]}}
                    :enums {}}
        refusals (constructs/field-refusals
                  with-group "p.M" (first (get-in with-group [:messages "p.M" :fields])))]
    (is (= [:unknown-field-type] (mapv :reason refusals)))
    (is (not (contains? db/known-types :group)))
    (testing "CONTROL: the same field with an emittable type draws no refusal"
      (is (empty? (constructs/field-refusals
                   with-group "p.M" {:number 1 :name "leg" :type :sint32}))))))
