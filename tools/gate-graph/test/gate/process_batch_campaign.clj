(ns gate.process-batch-campaign
  "Attributed batch acquisition, stability and coverage-unit faults with frozen full-suite baselines."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def batch-faults
  [{:id "missing-enrollment-admitted"
    :anchor "(when-not (= (set (map :id gates)) (set (keys bindings)))" :replacement "(when false"
    :test "invalid-enrollment-and-policy-refuse-before-creating-reports"}
   {:id "command-witness-relabels-work"
    :anchor "(not= (:coverage gate) (command-expectation (:command gate) (:cwd gate)))" :replacement "false"
    :test "command-witness-refuses-other-units-and-invocation-identities"}
   {:id "missing-input-launched"
    :anchor "(and (= expected before-source) before-digest)" :replacement "(= expected before-source)"
    :test "missing-input-and-unlisted-stdin-refuse-before-any-process"}
   {:id "witness-error-hidden"
    :anchor "(catch Throwable _ {:coverage nil :error? true})" :replacement "(catch Throwable _ {:coverage nil :error? false})"
    :test "witness-failure-keeps-process-evidence-and-omits-exception-values"}
   {:id "publication-error-hidden"
    :anchor "(catch Exception _ (assoc-in capture [:validation :publication-error?] true))" :replacement "(catch Exception _ capture)"
    :test "failed-publication-is-retained-in-the-enclosing-report"}])

(def capture-faults
  [{:id "controller-change-passes"
    :anchor "(not= (:source-digest capture) source-before source-after)" :replacement "false"
    :test "changed-controller-retains-first-execution-and-refuses-the-next-launch"}
   {:id "input-change-passes" :anchor "(not= inputs-before inputs-after)" :replacement "false"
    :test "changed-input-preserves-the-executed-command-and-refuses-success"}])

(defn -main
  "Require assertion kills with passing controls for the batch boundary and both independent coverage-unit guards."
  [output-parent]
  (doseq [[scope source namespace-name control faults]
          [[:process-batch "src/gate/process_batch.clj" "gate.process-batch-test"
            "actual-process-batch-publishes-roundtrippable-source-bound-evidence" batch-faults]
           [:process-capture-verdict "src/gate/verdict.cljc" "gate.process-batch-test"
            "actual-process-batch-publishes-roundtrippable-source-bound-evidence" capture-faults]
           [:coverage-unit-verdict "src/gate/verdict.cljc" "gate.run-test"
            "cosmetic-labels-and-unrelated-work-do-not-invalidate"
            [{:id "verdict-unit-confusion"
              :anchor "(= (get-in gate [:coverage :unit]) (get-in result [:coverage :unit]))" :replacement "true"
              :test "generated-coverage-units-bind-verdicts-and-receipts"}]]
           [:coverage-unit-cache "src/gate/cache.cljc" "gate.run-test"
            "cosmetic-labels-and-unrelated-work-do-not-invalidate"
            [{:id "receipt-unit-confusion" :anchor "(= (get-in gate [:coverage :unit]) (:unit coverage))" :replacement "true"
              :test "generated-coverage-units-bind-verdicts-and-receipts"}]]]]
    (binding [campaign/*scope* scope campaign/*mutation-source* source campaign/*test-namespace* namespace-name
              campaign/*control* control campaign/*faults* faults]
      (campaign/-main output-parent))))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
