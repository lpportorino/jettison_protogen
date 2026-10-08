(ns lint-gate.kondo-types-test
  "Regression cases for nullable and open Malli return types in clj-kondo."
  (:require [clj-kondo.core :as kondo]
            [clj-kondo.impl.types.utils :as types]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [malli.clj-kondo :as mk]))

(def signatures
  "Synthetic function contracts emitted by the installed Malli release."
  {'lookup [:=> [:cat] [:maybe [:map [:count :int]]]]
   'flexible [:=> [:cat] :map]
   'optional [:=> [:cat] [:map [:count {:optional true} :int]]]
   'known [:=> [:cat] [:map [:count :int]]]
   'wrong [:=> [:cat] [:map [:count :string]]]
   'unknown [:=> [:cat] :any]
   'consume [:=> [:cat [:map [:count :int]]] :any]
   'text [:=> [:cat :string] :any]})

(defn findings
  "Analyze one synthetic form without consulting project config or caches."
  [form]
  (let [config (mk/linter-config
                (mapcat (fn [[fn-name schema]]
                          (mk/from {:ns 'example :name fn-name :schema schema}))
                        signatures))
        source (str "(ns example) (declare lookup flexible optional known wrong unknown consume text) " form)]
    (:findings (with-in-str source
                 (kondo/run! {:lint ["-"] :cache false
                              :config (assoc config :config-paths [])})))))

(deftest possible-values-remain-possible
  (doseq [form ["(if-let [item (lookup)] (:count item) :missing)"
                "(if (:count (lookup)) :present :missing)"
                "(if (or (unknown) (lookup)) :present :missing)"
                "(consume (flexible))"
                "(if (:count (flexible)) :present :missing)"
                "(if (:count (optional)) :present :missing)"]]
    (testing form (is (= [] (findings form))))))

(deftest nullable-union-retains-a-falsy-alternative
  (testing "nil remains possible when a nullable map is united with arbitrary truthy values"
    (is (false? (types/never-falsy?
                 (types/absorb #{:truthy {:type :map :nilable true :val {}}})))))
  (testing "control: a non-nullable map and arbitrary truthy values are always truthy"
    (is (true? (types/never-falsy?
                (types/absorb #{:truthy {:type :map :val {}}}))))))

(deftest genuine-errors-still-reported
  (doseq [[form message]
          [["(consume {})" "Missing required key: :count"]
           ["(consume {:count false})" "Expected: integer"]
           ["(consume (wrong))" "Expected: integer"]
           ["(text (:count (optional)))" "Expected: string"]
           ["(text (:count (lookup)))" "Expected: string"]
           ["(if (known) :present :missing)" "Condition always true"]
           ["(if (or (unknown) (known)) :present :missing)" "Condition always true"]]]
    (testing form
      (let [actual (findings form)]
        (is (= 1 (count actual)))
        (is (some #(str/includes? (:message %) message) actual))))))

(defn -main
  "Exit unsuccessfully on failures, errors, or an empty regression suite."
  [& _]
  (let [{test-count :test :keys [fail error]} (run-tests 'lint-gate.kondo-types-test)]
    (shutdown-agents)
    (System/exit (if (and (pos? test-count) (zero? fail) (zero? error)) 0 1))))
