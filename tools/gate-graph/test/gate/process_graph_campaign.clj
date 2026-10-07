(ns gate.process-graph-campaign
  "Attributed process projection faults require assertion failures, passing controls and full baselines."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def faults
  [{:id "wrong-clock-accepted" :anchor "(not= (:clock observed-batch) (:clock process))" :replacement "false"
    :test "wrong-clock-command-source-coverage-and-interval-refuse-before-projection"}
   {:id "wrong-command-accepted" :anchor "(not= (:command gate) (:command capture))" :replacement "false"
    :test "wrong-clock-command-source-coverage-and-interval-refuse-before-projection"}
   {:id "wrong-source-accepted" :anchor "(not= (:source-digest options) (:source-digest capture))" :replacement "false"
    :test "wrong-clock-command-source-coverage-and-interval-refuse-before-projection"}
   {:id "unbound-process-verdict"
    :anchor "(when-not (= (:work dispatch) (verdict/process-capture-result gate capture))"
    :replacement "(when false"
    :test "wrong-clock-command-source-coverage-and-interval-refuse-before-projection"}
   {:id "incorrect-supervision-offset" :anchor "start (:offset-ns process)"
    :replacement "start (d/add (:offset-ns process) \"1\")"
    :test "exact-large-offsets-preserve-process-duration"}
   {:id "unknown-cleanup-complete" :anchor "(:observed-processes-stopped? observation)" :replacement "true"
    :test "failures-cancellation-limits-and-failed-launch-remain-distinct-evidence"}])

(defn -main
  "Freeze source and logs, then require all selected projection faults to be assertion-killed."
  [output-parent]
  (binding [campaign/*scope* :process-projection
            campaign/*mutation-source* "src/gate/process_graph.cljc"
            campaign/*test-namespace* "gate.process-graph-test"
            campaign/*control* "real-coordinator-process-and-dependency-clock-domains-join"
            campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
