(ns protocol-gen.constraints-test
  (:require [clojure.test :refer [deftest is testing]]
            [protocol-gen.constraints :as constraints]))

(defn- opts
  [fld]
  (constraints/field-options "p.M" fld))

(deftest numeric-bounds-land-in-the-fields-own-rule-set
  (is (= ["(buf.validate.field).double = {gte: -90, lte: 90}"]
         (opts {:number 1 :name "a" :type :double :constraints {:gte -90 :lte 90}})))
  (is (= ["(buf.validate.field).uint32 = {gt: 0, lt: 10}"]
         (opts {:number 1 :name "a" :type :uint32 :constraints {:gt 0 :lt 10}}))))

(deftest string-and-bytes-share-a-constraint-key-and-not-a-rule-set
  ;; `min_len` is a string rule on a string and a bytes rule on bytes; the rule
  ;; set is chosen by the FIELD's type, never by the constraint.
  (is (= ["(buf.validate.field).string = {max_len: 8, min_len: 1}"]
         (opts {:number 1 :name "a" :type :string :constraints {:min-len 1 :max-len 8}})))
  (is (= ["(buf.validate.field).bytes = {min_len: 1}"]
         (opts {:number 1 :name "a" :type :bytes :constraints {:min-len 1}}))))

(deftest list-constraints-land-in-the-repeated-rule-set
  (is (= ["(buf.validate.field).repeated = {max_items: 4, min_items: 1}"]
         (opts {:number 1 :name "a" :type :int32 :repeated true
                :constraints {:min-items 1 :max-items 4}}))))

(deftest a-list-constraint-on-a-non-list-field-is-refused
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"constraint-type-mismatch"
       (opts {:number 1 :name "a" :type :int32 :constraints {:min-items 1}}))))

(deftest a-constraint-that-cannot-apply-to-the-type-is-refused
  (testing "a numeric bound on a string"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"constraint-type-mismatch"
         (opts {:number 1 :name "a" :type :string :constraints {:gte 1}}))))
  (testing "a length on a number"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"constraint-type-mismatch"
         (opts {:number 1 :name "a" :type :int32 :constraints {:min-len 1}}))))
  (testing "any type-scoped rule on a message field, which has no rule set"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"constraint-type-mismatch"
         (opts {:number 1 :name "a" :type :message :type-ref "p.X"
                :constraints {:gte 1}})))))

(deftest an-unrecognised-constraint-is-refused-rather-than-dropped
  ;; Dropping it would emit a schema WEAKER than the one it claims to project,
  ;; with nothing in the file saying so.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-constraint"
       (opts {:number 1 :name "a" :type :int32 :constraints {:no-such-rule 1}}))))

(deftest enum-and-required-render-in-their-own-places
  (is (= ["(buf.validate.field).enum = {defined_only: true, not_in: [0]}"]
         (opts {:number 1 :name "a" :type :enum :type-ref "p.E"
                :constraints {:defined-only true :not-in [0]}})))
  (testing "required is an option on the field, not a member of a rule set"
    (is (= ["(buf.validate.field).required = true"]
           (opts {:number 1 :name "a" :type :message :type-ref "p.X"
                  :constraints {:required true}})))))

(deftest string-values-are-quoted-and-lists-bracketed
  (is (= ["(buf.validate.field).string = {in: [\"a\", \"b\"], pattern: \"^x$\"}"]
         (opts {:number 1 :name "a" :type :string
                :constraints {:pattern "^x$" :in ["a" "b"]}}))))

(deftest emission-is-deterministic
  (let [fld {:number 1 :name "a" :type :double
             :constraints {:lte 1 :gte -1 :example [1 2]}}]
    (is (= (opts fld) (opts fld)))
    (testing "and rule members are sorted, so a map's iteration order cannot leak"
      (is (= ["(buf.validate.field).double = {example: [1, 2], gte: -1, lte: 1}"]
             (opts fld))))))

(deftest a-field-with-no-constraints-carries-no-options-and-needs-no-import
  (is (= [] (opts {:number 1 :name "a" :type :bool})))
  (is (= [] (constraints/imports-for false)))
  (is (= ["buf/validate/validate.proto"] (constraints/imports-for true))))

(deftest element-rules-emit-inside-the-repeated-rule-set
  ;; `max_items` judges the LIST and the `items` block judges each ELEMENT; both
  ;; live in the same emitted rule set and neither may swallow the other.
  (is (= ["(buf.validate.field).repeated = {items: {string: {max_len: 31}}, max_items: 8}"]
         (opts {:number 1 :name "a" :type :string :repeated true
                :constraints {:max-items 8 :items {:string {:max-len 31}}}}))))

(deftest element-rules-on-a-non-list-field-are-refused
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"constraint-type-mismatch"
       (opts {:number 1 :name "a" :type :string
              :constraints {:items {:string {:max-len 31}}}}))))

(deftest element-rules-declared-under-another-types-rule-set-are-refused
  ;; Re-spelling them as the field's own type would substitute one type's rules
  ;; for another's: protoc accepts the result and the runtime then judges the
  ;; value under rules the source never declared.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"constraint-type-mismatch"
       (opts {:number 1 :name "a" :type :string :repeated true
              :constraints {:items {:int32 {:gte 1}}}}))))

(deftest element-rules-naming-more-than-one-rule-set-are-refused
  ;; protovalidate's `items` is one FieldRules, whose type arm is a oneof, so a
  ;; second rule set is not expressible rather than merely unusual.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"constraint-type-mismatch"
       (opts {:number 1 :name "a" :type :string :repeated true
              :constraints {:items {:string {:max-len 31} :bytes {:max-len 31}}}}))))

(deftest an-element-constraint-that-judges-the-list-is-refused
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"constraint-type-mismatch"
       (opts {:number 1 :name "a" :type :string :repeated true
              :constraints {:items {:string {:max-items 2}}}}))))

(deftest an-unrecognised-element-constraint-is-refused-rather-than-dropped
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-constraint"
       (opts {:number 1 :name "a" :type :string :repeated true
              :constraints {:items {:string {:no-such-rule 1}}}}))))
