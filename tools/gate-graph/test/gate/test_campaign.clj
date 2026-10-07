(ns gate.test-campaign
  "Attributed faults in test inventory, dynamic ownership, acquisition budgets and graph projection."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def observer-faults
  [{:id "assertion-budget-disabled" :anchor "(>= (:assertions @state) (:max-assertions options))"
    :replacement "false" :test "assertion-and-invocation-limits-preserve-incomplete-evidence"}
   {:id "dynamic-owner-erased" :anchor "(binding [*invocation* index] (original-test-var v))"
    :replacement "(binding [*invocation* nil] (original-test-var v))"
    :test "joined-future-assertions-and-nested-tests-have-one-owner"}
   {:id "normal-return-lost" :anchor "(vreset! returned? true)" :replacement "(vreset! returned? false)"
    :test "real-test-vars-keep-fixtures-and-independent-coverage"}
   {:id "closed-invocation-accepted" :anchor "(when (:closed? @state)" :replacement "(when false"
    :test "nested-observers-and-closed-callbacks-do-not-corrupt-the-enclosing-suite"}])
(def projection-faults
  [{:id "missing-tests-ignored" :anchor "(seq (remove observed expected))" :replacement "false"
    :test "omitted-empty-failing-and-thrown-tests-never-earn-passing-coverage"}
   {:id "partial-assertions-measured" :anchor "(and partial? (not= :tests quantity))" :replacement "false"
    :test "assertion-and-invocation-limits-preserve-incomplete-evidence"}
   {:id "identity-label-mismatch-accepted"
    :anchor "(some #(and (contains? expected (:key %)) (not= (get expected (:key %)) (:label %))) executions)"
    :replacement "false" :test "graph-projection-refuses-forged-success-and-record-inconsistency"}
   {:id "attempt-sequence-ignored"
    :anchor "(some (fn [[_ entries]] (not= (mapv :attempt entries) (vec (range 1 (inc (count entries))))))\n                    (group-by :key executions))"
    :replacement "false" :test "graph-projection-refuses-forged-success-and-record-inconsistency"}])

(def artifact-faults
  [{:id "changed-source-passed" :anchor "(= before (source-digest roots))" :replacement "true"
    :test "source-change-preserves-executions-and-refuses-coverage"}
   {:id "source-output-accepted" :anchor "(.startsWith path (.toRealPath source (make-array LinkOption 0)))"
    :replacement "false" :test "source-directory-and-symlink-alias-cannot-contain-report-output"}
   {:id "admission-parity-ignored" :anchor "(when-not (= value (admission/decode text target admission/default-limits))"
    :replacement "(when false" :test "record-publication-requires-admission-parity-and-create-only-files"}
   {:id "artifact-overwrite-accepted" :anchor "[StandardOpenOption/WRITE StandardOpenOption/CREATE_NEW]"
    :replacement "[StandardOpenOption/WRITE StandardOpenOption/CREATE StandardOpenOption/TRUNCATE_EXISTING]"
    :test "record-publication-requires-admission-parity-and-create-only-files"}])

(defn -main
  "Require full baselines, uniquely landed faults, assertion failures and passing independent controls."
  [output-parent]
  (binding [campaign/*scope* :test-observer campaign/*mutation-source* "src/gate/clojure_test.clj"
            campaign/*test-namespace* "gate.clojure-test-test"
            campaign/*control* "unknown-empty-and-duplicate-namespace-inventories-refuse-before-execution"
            campaign/*faults* observer-faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :test-projection campaign/*mutation-source* "src/gate/test_graph.cljc"
            campaign/*test-namespace* "gate.clojure-test-test"
            campaign/*control* "unknown-empty-and-duplicate-namespace-inventories-refuse-before-execution"
            campaign/*faults* projection-faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :test-artifact campaign/*mutation-source* "src/gate/test_artifact.clj"
            campaign/*test-namespace* "gate.test-artifact-test"
            campaign/*control* "stable-source-publishes-both-records-and-existing-run-refuses"
            campaign/*faults* artifact-faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
