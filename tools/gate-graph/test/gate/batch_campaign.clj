(ns gate.batch-campaign
  "Attributed clock, batch reconciliation, acquisition and declared-edge faults in fresh workers."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def projection-faults
  [{:id "different-clock-accepted" :anchor "(not= (:clock batch) (:clock capture))" :replacement "false"
    :test "projection-reconciles-clock-coverage-roster-status-and-prerequisite-order"}
   {:id "capture-interval-ignored"
    :anchor "(when-not (and (d/before-or-equal? (:start-ns interval) start)"
    :replacement "(when (and false (d/before-or-equal? (:start-ns interval) start)"
    :test "projection-reconciles-clock-coverage-roster-status-and-prerequisite-order"}
   {:id "capture-offset-shifted" :anchor "offset (:offset-ns capture)" :replacement "offset (d/add (:offset-ns capture) \"1\")"
    :test "actual-tests-and-adapter-boundaries-have-separate-exact-intervals"}
   {:id "missing-capture-complete"
    :anchor "(true? (get-in by-capture [(:gate dispatch) :observation :complete?]))"
    :replacement "true" :test "unobserved-passing-adapters-never-become-fabricated-passing-executions"}
   {:id "source-expansion-budget-disabled" :anchor "(> (+ 2 (* 3 (count captures))) 4096)" :replacement "false"
    :test "graph-expansion-budget-refuses-before-copying-valid-many-gate-captures"}])

(def native-faults
  [{:id "missing-suite-enrollment-accepted"
    :anchor "(when-not (= (set (map :id gates)) (set (keys suites)))"
    :replacement "(when false"
    :test "enrollment-and-native-policy-refuse-before-any-work-or-output"}
   {:id "incomplete-acquisition-promoted"
    :anchor "(and (= :passed (:status batch)) (get-in graph [:run :complete?]))"
    :replacement "(= :passed (:status batch))"
    :test "incomplete-acquisition-cannot-be-promoted-by-a-passing-batch"}])

(defn -main
  "Freeze all module bytes and require passing full baselines, independent controls and assertion kills."
  [output-parent]
  (binding [campaign/*scope* :native-clock campaign/*mutation-source* "src/gate/clock.clj"
            campaign/*test-namespace* "gate.batch-graph-test"
            campaign/*control* "actual-tests-and-adapter-boundaries-have-separate-exact-intervals"
            campaign/*faults* [{:id "negative-elapsed-accepted" :anchor "(when (neg? elapsed)"
                                :replacement "(when false"
                                :test "signed-monotonic-ticks-wrap-without-wall-clock-or-floating-point-alignment"}]]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :batch-projection campaign/*mutation-source* "src/gate/batch_graph.cljc"
            campaign/*test-namespace* "gate.batch-graph-test"
            campaign/*control* "undispatched-cancellation-retains-decisions-and-no-test-time"
            campaign/*faults* projection-faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :native-test-batch campaign/*mutation-source* "src/gate/test_batch.clj"
            campaign/*test-namespace* "gate.test-batch-test"
            campaign/*control* "pre-cancellation-is-a-complete-decision-without-running-tests"
            campaign/*faults* native-faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :graph-evidence campaign/*mutation-source* "src/gate/graph.cljc"
            campaign/*test-namespace* "gate.graph-test"
            campaign/*control* "spawn-and-join-form-a-valid-event-dag"
            campaign/*faults* [{:id "declared-edge-imposes-time"
                                :anchor "(and (= :observed (:evidence edge)) from to (not (d/before-or-equal? from to)))"
                                :replacement "(and true from to (not (d/before-or-equal? from to)))"
                                :test "declared-dependencies-do-not-invent-time-order-and-invalidation-is-not-observed-causation"}]]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
