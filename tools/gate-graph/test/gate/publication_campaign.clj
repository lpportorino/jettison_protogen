(ns gate.publication-campaign
  "Independent completion/publication faults with frozen sources and attributed assertion failures."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def publication-faults
  [{:id "missing-source-observation"
    :anchor "(when-not (= expected (inputs/outputs! source-directory gate evidence limits))"
    :replacement "(when false" :test "unavailable-source-observation-refuses-before-staging"}
   {:id "unbounded-copy" :anchor "(when (> (swap! budget + n) (:bytes limits))"
    :replacement "(when (and false (> (swap! budget + n) (:bytes limits)))"
    :test "output-copy-budget-remains-enforced-after-observation"}
   {:id "unverified-staged-copy" :anchor "(when-not (= [(assoc file :path relative)] actual)"
    :replacement "(when (and false (not= [(assoc file :path relative)] actual))"
    :test "bytes-changing-after-source-observation-refuse-before-replacement"}
   {:id "partial-io-hidden"
    :anchor "(catch java.io.IOException _ {:status (if (seq @installed) :partial :refused)"
    :replacement "(catch java.io.IOException _ {:status :refused"
    :test "failed-second-move-reports-exact-installed-subset"}
   {:id "final-verification-omitted"
    :anchor "(if (= expected (inputs/outputs! destination-directory gate evidence limits))"
    :replacement "(if true" :test "final-output-verification-cannot-be-replaced-by-staging-evidence"}])

(def completion-faults
  [{:id "executed-snapshot-not-keyed"
    :anchor "(not= before (get-in result [:observation :snapshot]))" :replacement "false"
    :test "keyed-snapshot-and-live-workspace-must-both-match-executed-inputs"}
   {:id "live-workspace-changes-ignored"
    :anchor "(not= before (inputs/observe! (:directory request) gate (:evidence request) (:input-limits request)))"
    :replacement "false" :test "keyed-snapshot-and-live-workspace-must-both-match-executed-inputs"}
   {:id "incomplete-snapshot-accepted" :anchor "(not (:complete? before))" :replacement "false"
    :test "incomplete-keyed-snapshot-cannot-authorize-publication"}
   {:id "cleanup-not-required" :anchor "(not removed?)" :replacement "(= removed? :never)"
    :test "failed-or-unremoved-container-never-asks-for-coverage-or-publishes"}
   {:id "publication-error-laundered" :anchor "(if (= :installed (:status publication)) work"
    :replacement "(if true work" :test "successful-execution-needs-coverage-and-verified-output-publication"}
   {:id "late-cancellation-ignored"
    :anchor "@cancellation {:work {:outcome :cancelled :coverage nil :reason :cancellation-requested} :publication skipped}"
    :replacement "false {:work {:outcome :cancelled :coverage nil :reason :cancellation-requested} :publication skipped}"
    :test "cancellation-from-coverage-observer-prevents-output-publication"}])

(defn -main
  "Prove publication and completion guards separately, with clean controls and complete suite baselines."
  [output-parent]
  (binding [campaign/*scope* :output-publication campaign/*mutation-source* "src/gate/publish.clj"
            campaign/*test-namespace* "gate.publish-test"
            campaign/*control* "executable-mode-and-new-output-parents-are-published"
            campaign/*faults* publication-faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :contained-completion campaign/*mutation-source* "src/gate/contained.clj"
            campaign/*test-namespace* "gate.contained-test"
            campaign/*control* "successful-execution-needs-coverage-and-verified-output-publication"
            campaign/*faults* (vec (remove #(= "publication-error-laundered" (:id %)) completion-faults))]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :contained-completion campaign/*mutation-source* "src/gate/contained.clj"
            campaign/*test-namespace* "gate.contained-test"
            campaign/*control* "cancellation-from-coverage-observer-prevents-output-publication"
            campaign/*faults* (filterv #(= "publication-error-laundered" (:id %)) completion-faults)]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
