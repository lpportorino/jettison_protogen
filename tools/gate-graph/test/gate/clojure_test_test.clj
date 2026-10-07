(ns gate.clojure-test-test
  "Independent inventory, fixture, assertion ownership, concurrency and graph inspection controls."
  (:require [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [example.observed-suite :as fixture]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.clojure-test :as observer]
            [gate.graph :as graph]
            [gate.measure :as measure]
            [gate.query :as query]
            [gate.run-contract :as r]
            [gate.test-graph :as projection]
            [malli.core :as m]
            [malli.generator :as mg])
  (:import [java.io StringWriter]
           [java.util.concurrent CountDownLatch]))

(def options {:run "test-run" :gate "suite-tests" :label "Synthetic suite"
              :source-digest (canonical/sha256 "synthetic-source") :max-tests 20 :max-assertions 100})

(defn capture
  "Collect synthetic runner behavior while keeping intentional red output out of the enclosing test log."
  [mode policy]
  (binding [fixture/*mode* mode test/*test-out* (StringWriter.)]
    (observer/run! policy '[example.observed-suite])))
(m/=> capture [:=> [:cat :keyword observer/Options] r/TestObservation])

(defn refusal
  "Return bounded error data without swallowing unexpected native exceptions."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))
(m/=> refusal [:=> [:cat [:=> [:cat] [:fn (constantly true)]]] [:maybe [:or observer/Failure projection/Failure]]])

(deftest real-test-vars-keep-fixtures-and-independent-coverage
  (let [events (atom [])
        result (binding [fixture/*events* events] (capture :normal options))]
    (is (= [:once-start :each-start :each-end :each-start :each-end :once-end] @events))
    (is (= :passed (:status result)))
    (is (:complete? result))
    (is (= 2 (get-in result [:coverage :count])))
    (is (= #{"example.observed-suite/first-test" "example.observed-suite/second-test"}
           (set (map :label (:inventory result)))))
    (is (= [{:pass 1 :fail 0 :error 0} {:pass 1 :fail 0 :error 0}] (mapv :counts (:executions result))))
    (is (= result (admission/decode (canonical/encode result 65536) :test-observation admission/default-limits)))))

(deftest omitted-empty-failing-and-thrown-tests-never-earn-passing-coverage
  (doseq [[mode status problem] [[:omit :error :missing-tests] [:empty :error :empty-assertions]
                                 [:fixture-error :error :runner-error] [:fixture-assertion :error :unattributed-assertions]
                                 [:failure :failed nil] [:error :error nil]]]
    (let [result (capture mode options)]
      (is (= status (:status result)) (str mode))
      (is (nil? (:coverage result)) (str mode))
      (is (if problem (some #{problem} (:problems result)) (:complete? result)) (str mode))
      (is (not (str/includes? (canonical/encode result 65536) "sensitive")))
      (is (empty? (graph/findings (projection/project result)))))))

(deftest assertion-and-invocation-limits-preserve-incomplete-evidence
  (let [limited (capture :normal (assoc options :max-assertions 1))
        graph (projection/project limited)]
    (is (= :error (:status limited)))
    (is (some #{:assertion-limit} (:problems limited)))
    (is (= 1 (reduce + (map #(get-in % [:counts :pass]) (:executions limited)))))
    (is (every? #(= :partial (:status %)) (filter #(= :assertions (:quantity %)) (:measurements graph)))))
  (let [limited (capture :nested (assoc options :max-tests 2))]
    (is (= :error (:status limited)))
    (is (some #{:test-limit} (:problems limited)))
    (is (= 2 (count (:executions limited)))))
  (is (= :test-limit (:code (refusal #(capture :normal (assoc options :max-tests 1)))))))

(deftest joined-future-assertions-and-nested-tests-have-one-owner
  (let [joined (capture :joined options)]
    (is (= :passed (:status joined)))
    (is (= {:pass 0 :fail 0 :error 0} (:unattributed joined)))
    (is (= [1 1] (mapv #(get-in % [:counts :pass]) (:executions joined)))))
  (let [nested (capture :nested options)
        [parent child repeated] (:executions nested)]
    (is (= :passed (:status nested)))
    (is (= (:id parent) (:parent child)))
    (is (nil? (:parent repeated)))
    (is (= [1 2] (mapv :attempt [child repeated])))
    (is (= [1 1 1] (mapv #(get-in % [:counts :pass]) (:executions nested))))))

(deftest concurrent-invocations-and-independent-observers-do-not-mix-state
  (let [result (binding [fixture/*barrier* (CountDownLatch. 2)] (capture :parallel options))
        [a b] (:executions result)]
    (is (= :passed (:status result)))
    (is (= [2 2] (mapv #(get-in % [:counts :pass]) (:executions result))))
    (is (< (bigint (get-in b [:interval :start-ns])) (bigint (get-in a [:interval :end-ns])))))
  (let [workers [(future (capture :normal options)) (future (capture :failure options))]
        results (mapv deref workers)]
    (is (= [:passed :failed] (mapv :status results)))
    (is (= [0 1] (mapv #(reduce + (map (comp :fail :counts) (:executions %))) results)))))

(deftest projected-tests-are-drillable-and-counts-aggregate-without-wall-time-inference
  (let [result (capture :normal options) graph (projection/project result)
        context (query/prepare graph)
        budget {:visits 100 :rows 100 :bytes 65536}
        tests (query/page context {:select {:op :children :anchor "suite"} :budget budget})
        metrics (filter #(= :assertions (:quantity %)) (:measurements graph))
        total (measure/aggregate context {:op :sum :measurements (mapv :id metrics)
                                          :budget {:pairs 10 :bytes 65536}})]
    (is (= 2 (count (:rows tests))))
    (is (= :complete (:status total)))
    (is (= "2" (:value total)))
    (is (= #{:tests :assertions :assertions-passed :assertions-failed :assertions-errored}
           (set (map :quantity (:measurements graph)))))
    (is (empty? (graph/findings graph)))))

(deftest graph-projection-refuses-forged-success-and-record-inconsistency
  (let [failed (capture :omit options)
        passed (capture :normal options)]
    (doseq [changed [(assoc failed :complete? true :status :passed :coverage (:coverage passed))
                     (update passed :inventory conj (first (:inventory passed)))
                     (assoc-in passed [:executions 0 :label] "wrong identity")
                     (assoc-in passed [:executions 0 :attempt] 2)]]
      (is (= :invalid-test-observation (:code (refusal #(projection/project changed))))))))

(deftest generated-assertion-counts-have-independent-verdict-and-metric-oracles
  (let [base (capture :normal options)
        result (tc/quick-check
                100
                (prop/for-all [counts (mg/generator r/TestCounts)]
                              (let [changed (assoc-in base [:executions 0 :counts] counts)
                                    judged (merge changed (projection/judge (:inventory changed) (:executions changed) (:unattributed changed) []))
                                    graph (projection/project judged)
                                    expected (cond (pos? (:error counts)) :error (pos? (:fail counts)) :failed :else :passed)
                                    total (first (filter #(and (= "test-0" (:node %)) (= :assertions (:quantity %))) (:measurements graph)))]
                                (and (= expected (:status judged)) (= (str (+ (:pass counts) (:fail counts) (:error counts))) (:value total))
                                     (empty? (graph/findings graph)))))
                :seed 20261008)]
    (is (:pass? result) (pr-str result))))

(deftest fixture-counts-and-missing-tests-remain-visible-in-the-graph
  (let [graph (projection/project (capture :fixture-assertion options))]
    (is (some #(and (= "suite" (:node %)) (= :assertions (:quantity %)) (= "1" (:value %))) (:measurements graph)))
    (is (some #(= "problem/unattributed-assertions" (:id %)) (:nodes graph))))
  (let [graph (projection/project (capture :omit options))]
    (is (false? (get-in graph [:run :complete?])))
    (is (some #(and (= :test (:kind %)) (= :refused (:outcome %))
                    (= :missing-evidence (get-in % [:reason :code]))) (:nodes graph)))))

(deftest nested-observers-and-closed-callbacks-do-not-corrupt-the-enclosing-suite
  (let [result (capture :nested-observer options)]
    (is (= :passed (:status result)))
    (is (= 2 (count (:executions result))))
    (is (= [1 1] (mapv #(get-in % [:counts :pass]) (:executions result)))))
  (let [state (atom {:closed? true :executions [] :attempts {}})]
    (is (= :test-observer-closed
           (:code (refusal #(#'observer/assertion! state options nil :pass)))))
    (is (= :test-observer-closed
           (:code (refusal #(#'observer/begin! state options #'fixture/first-test (System/nanoTime) nil)))))
    (is (= {:closed? true :executions [] :attempts {}} @state))))

(deftest unknown-empty-and-duplicate-namespace-inventories-refuse-before-execution
  (doseq [namespaces ['[example.observed-suite example.observed-suite]
                      '[example.namespace-that-does-not-exist]
                      '[clojure.string]]]
    (is (= :invalid-test-inventory (:code (refusal #(observer/inventory namespaces)))))))

(deftest custom-runner-observes-cold-and-repeated-work-against-the-whole-inventory
  (let [result (binding [fixture/*mode* :normal test/*test-out* (StringWriter.)]
                 (observer/run-with! options '[example.observed-suite]
                                     (fn []
                                       (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                                         (test/test-var #'fixture/first-test))
                                       (test/run-tests 'example.observed-suite)
                                       nil)))
        entries (filter #(= "example.observed-suite/first-test" (:label %)) (:executions result))]
    (is (= :passed (:status result)))
    (is (= 2 (count (:inventory result))))
    (is (= 3 (count (:executions result))))
    (is (= [1 2] (mapv :attempt entries)))
    (is (= 3 (get-in result [:coverage :count])))
    (is (= 3 (reduce + (map #(get-in % [:counts :pass]) (:executions result)))))))

(deftest custom-runner-cannot-omit-work-or-hide-an-exception-after-passing-tests
  (binding [fixture/*mode* :normal test/*test-out* (StringWriter.)]
    (let [omitted (observer/run-with! options '[example.observed-suite] (fn [] nil))
          thrown (observer/run-with! options '[example.observed-suite]
                                     (fn [] (test/run-tests 'example.observed-suite)
                                       (throw (ex-info "Synthetic runner failure" {}))))]
      (is (= :error (:status omitted)))
      (is (some #{:missing-tests} (:problems omitted)))
      (is (some #{:empty-assertions} (:problems omitted)))
      (is (nil? (:coverage omitted)))
      (is (= :error (:status thrown)))
      (is (some #{:runner-error} (:problems thrown)))
      (is (= 2 (count (:executions thrown))))
      (is (nil? (:coverage thrown))))))
