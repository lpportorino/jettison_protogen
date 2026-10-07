(ns gate.diagnostic-test
  "Misused drill APIs fail with bounded schema paths instead of echoing a graph or schema."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [example.adapter :as adapter]
            [example.adapter.child :as child]
            [gate.diagnostic :as diagnostic]
            [gate.diff :as diff]
            [gate.fixtures :as fixtures]
            [gate.query :as query]
            [gate.run-test :refer [error]]
            [malli.core :as m]))

(deftest consumer-enrollment-is-explicit-exact-and-keeps-compact-errors
  (diagnostic/install! '[example.adapter])
  (let [failure (error #(adapter/count-value "sensitive-value"))]
    (is (= :contract-input (:code failure)))
    (is (= "example.adapter/count-value" (:function failure)))
    (is (m/validate diagnostic/Failure failure))
    (is (not (str/includes? (pr-str failure) "sensitive-value"))))
  (is (= 3 (adapter/count-value 3)))
  (is (= -1 (try (child/count-value -1)
                 (catch clojure.lang.ExceptionInfo e (ex-data e))))))

(deftest graph-in-place-of-prepared-context-has-a-compact-actionable-error
  (let [graph (assoc-in (fixtures/example) [:nodes 0 :label] "private-sentinel-value")
        failure (error #(diff/prepare graph graph))]
    (is (= :contract-input (:code failure)))
    (is (= "gate.diff/prepare" (:function failure)))
    (is (m/validate diagnostic/Failure failure))
    (is (some #(= [0 :artifact] (:path %)) (:issues failure)))
    (is (not (str/includes? (pr-str failure) "private-sentinel-value")))
    (is (not (str/includes? (pr-str failure) "[:map")))))

(deftest malformed-drill-budget-reports-the-argument-and-field
  (let [context (query/prepare (fixtures/example))
        failure (error #(query/page context {:select {:op :nodes}
                                             :budget {:visits 20 :rows 0 :bytes 4096}}))]
    (is (= :contract-input (:code failure)))
    (is (m/validate diagnostic/Failure failure))
    (is (some #(= [1 :budget :rows] (:path %)) (:issues failure)))))
