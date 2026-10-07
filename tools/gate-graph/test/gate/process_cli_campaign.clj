(ns gate.process-cli-campaign
  "Selected shutdown faults judged through actual signalled JVM workers and independent normal-run controls."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def faults
  [{:id "shutdown-does-not-cancel"
    :anchor "(reset! cancellation true)" :replacement "(reset! cancellation false)"
    :test "cli-signals-cancel-observed-children-and-publish-report"}
   {:id "shutdown-skips-publication-wait"
    :anchor "(.await ^CountDownLatch completion (long shutdown-ms) TimeUnit/MILLISECONDS)"
    :replacement "true"
    :test "shutdown-waits-for-cooperative-witness-and-report-publication"}
   {:id "shutdown-wait-unbounded"
    :anchor "(.await ^CountDownLatch completion (long shutdown-ms) TimeUnit/MILLISECONDS)"
    :replacement "(do (.await ^CountDownLatch completion) true)"
    :test "shutdown-budget-refuses-to-hang-on-uncooperative-witness"}
   {:id "shutdown-incomplete-diagnostic-lost"
    :anchor "(prn {:code :process-shutdown-incomplete :subject nil})"
    :replacement "(prn {:code :process-shutdown-unavailable :subject nil})"
    :test "shutdown-budget-refuses-to-hang-on-uncooperative-witness"}])

(defn -main
  "Freeze the full module, require before/after baselines, and attribute each shutdown defect to assertions."
  [output-parent]
  (binding [campaign/*scope* :process-cli campaign/*mutation-source* "src/gate/process_batch.clj"
            campaign/*test-namespace* "gate.process-cli-test"
            campaign/*control* "cli-scope-preserves-normal-result-and-exception" campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
