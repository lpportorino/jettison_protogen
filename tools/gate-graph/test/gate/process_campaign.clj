(ns gate.process-campaign
  "Attributed actual-process faults with independent termination, output and work-witness controls."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def faults
  [{:id "intermediate-parents-killed-with-leaves"
    :anchor "(not (contains? parent-pids pid))" :replacement "true"
    :test "cleanup-keeps-intermediate-waiters-alive-to-reap-their-children"}
   {:id "reaping-window-skipped"
    :anchor "reap-deadline (+ started (quot duration 2))" :replacement "reap-deadline started"
    :test "cleanup-keeps-intermediate-waiters-alive-to-reap-their-children"}
   {:id "remaining-zombies-reported-stopped"
    :anchor "(stopped? @handles)\n        (do (cleanup-yield! cancellation interrupted) (recur)))"
    :replacement "true\n        (do (cleanup-yield! cancellation interrupted) (recur)))"
    :test "cleanup-keeps-intermediate-waiters-alive-to-reap-their-children"}
   {:id "finished-handle-retention"
    :anchor "(swap! handles #(into {} (filter (fn [[_ handle]] (.isAlive ^ProcessHandle handle))) %))"
    :replacement "nil" :test "process-budget-bounds-live-handles-not-finished-sequential-children"}
   {:id "stale-capacity-at-admission"
    :anchor "(when (>= (count @handles) limit) (prune-handles! handles))"
    :replacement "nil" :test "descendant-admission-rechecks-capacity-after-enumeration"}
   {:id "inherited-environment" :anchor "(.clear effective)" :replacement "nil"
    :test "argv-environment-cwd-and-stdin-remain-explicit"}
   {:id "unbounded-output" :anchor "(min n (- limit (:bytes @state)))" :replacement "n"
    :test "exact-log-limit-is-complete-and-one-more-byte-refuses"}
   {:id "ignored-timeout" :anchor "(>= (System/nanoTime) deadline) :timed-out" :replacement "false :timed-out"
    :test "timeout-kills-observed-waiting-child-and-keeps-its-reason"}
   {:id "direct-process-survives" :anchor "(when (.isAlive ^Process process) (.destroyForcibly ^Process process))" :replacement "nil"
    :test "direct-timeout-must-actually-stop-the-command"}])

(defn -main
  "Require landed unique faults, named assertion failures, clean controls and complete baselines."
  [output-parent]
  (binding [campaign/*scope* :process
            campaign/*mutation-source* "src/gate/process.clj"
            campaign/*test-namespace* "gate.process-test"
            campaign/*control* "missing-executable-relative-executable-and-jobserver-refuse"
            campaign/*faults* faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :process-verdict
            campaign/*mutation-source* "src/gate/verdict.cljc"
            campaign/*test-namespace* "gate.process-test"
            campaign/*control* "missing-executable-relative-executable-and-jobserver-refuse"
            campaign/*faults* [{:id "nonzero-exit-passes" :anchor "(not= 0 (:exit observation))" :replacement "false"
                                :test "exit-status-and-independent-work-witness-both-matter"}]]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
