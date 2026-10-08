(ns gate.archive-campaign
  "Attributed archive consistency, publication, run-bracketing and cancellation faults."
  (:require [gate.admission-campaign :as campaign]
            [gate.repository-campaign :as repository]
            [malli.core :as m]))

(def identity-faults
  [{:id "tree-hash-ignored" :anchor "(= (:content tree) (canonical/sha256 (canonical/encode (dissoc tree :content) 134217728)))"
    :replacement "true" :test "independent-tree-hash-and-hierarchy-refusals"}
   {:id "orphan-checkout-admitted" :anchor "(= (vec (sort children)) (subvec paths 1))"
    :replacement "true" :test "independent-tree-hash-and-hierarchy-refusals"}
   {:id "excluded-entry-admitted" :anchor "(some #(beneath? % full) excluded)"
    :replacement "false" :test "independent-tree-hash-and-hierarchy-refusals"}
   {:id "file-ancestry-ignored" :anchor "(some #(ancestor-entry? contains-path? %) paths)"
    :replacement "false" :test "path-ancestry-is-not-lexical-adjacency"}
   {:id "summary-entry-count-fabricated" :anchor ":entries (count (:entries tree))"
    :replacement ":entries 0" :test "summaries-bind-the-full-observed-tree"}])

(def archive-faults
  [{:id "equal-hash-inconsistent-headers" :anchor "(and (= (:content a) (:content b)) (not= a b))"
    :replacement "false" :test "archive-verdict-preserves-missing-and-changed-evidence"}
   {:id "failed-graph-laundered" :anchor "(some outcomes [:failed :error :blocked :refused])"
    :replacement "false" :test "archive-verdict-preserves-missing-and-changed-evidence"}
   {:id "missing-provenance-passes" :anchor "(not= :unchanged (:stability provenance))"
    :replacement "false" :test "archive-verdict-preserves-missing-and-changed-evidence"}
   {:id "archive-identity-ignored" :anchor "(when-not (= expected document) (refuse! :archive-identity))"
    :replacement "nil" :test "digest-status-provenance-and-scope-cannot-be-relabelled"}])

(def publication-faults
  [{:id "different-capture-graph-accepted"
    :anchor "(= graph (canonical/normalize-graph (reader/read-graph! (str graph-path) admission/default-limits)))"
    :replacement "true" :test "existing-capture-graph-is-reused-only-with-matching-content"}
   {:id "export-exclusion-ignored" :anchor "(some #(repository/beneath? % relative) (:excluded candidate))"
    :replacement "true" :test "export-requires-exclusion-in-current-and-observed-policies"}])

(def run-faults
  [{:id "before-observation-replaced-by-after"
    :anchor "graph (:acquisition before) (:acquisition after)"
    :replacement "graph (:acquisition after) (:acquisition after)" :test "changed-inputs-retain-the-run-and-prevent-success"}
   {:id "after-observation-skipped" :anchor "after (acquire! options cancellation)"
    :replacement "after before" :test "changed-inputs-retain-the-run-and-prevent-success"}
   {:id "archive-success-guard-ignored" :anchor "(when-not (= :passed (:status document))"
    :replacement "(when false" :test "changed-inputs-retain-the-run-and-prevent-success"}])

(def graph-faults
  [{:id "node-checks-skipped"
    :anchor "(mapcat #(node-findings run-end (get nodes (:parent %)) %) (:nodes graph))"
    :replacement "[]" :test "independent-invariant-mutations-name-the-intended-clause"}
   {:id "edge-checks-skipped"
    :anchor "(mapcat #(edge-findings (get nodes (get-in % [:from :node]))\n                                            (get nodes (get-in % [:to :node])) %) (:edges graph))"
    :replacement "[]" :test "independent-invariant-mutations-name-the-intended-clause"}
   {:id "measurement-checks-skipped"
    :anchor "(mapcat #(measurement-findings run-end (get nodes (:node %))\n                                                   (get resources (:resource %)) %) (:measurements graph))"
    :replacement "[]" :test "measurement-states-and-capabilities-are-not-zero-filled"}
   {:id "whole-graph-validated-per-record"
    :anchor "(mapcat #(node-findings run-end (get nodes (:parent %)) %) (:nodes graph))"
    :replacement "(mapcat #(do (m/validate c/Graph graph) (node-findings run-end (get nodes (:parent %)) %)) (:nodes graph))"
    :test "instrumented-validation-work-scales-with-records"}])

(def faults
  (into (into repository/faults
              (map #(assoc % :source "src/gate/admission.cljc" :namespace "gate.admission-test"
                           :control "canonical-and-human-edn-have-the-same-meaning") campaign/faults))
        (concat
         (mapcat (fn [[source namespace-name control selected]]
                   (map #(assoc % :source source :namespace namespace-name :control control) selected))
                 [["src/gate/repository_identity.cljc" "gate.archive-test" "malformed-policy-and-summary-refusals" identity-faults]
                  ["src/gate/graph.cljc" "gate.graph-test" "complete-execution-and-greater-than-wall-cpu-are-valid" graph-faults]
                  ["src/gate/archive.cljc" "gate.archive-test" "summaries-bind-the-full-observed-tree" archive-faults]
                  ["src/gate/archive_io.clj" "gate.archive-io-test" "archive-publication-has-matching-edn-html-and-identity" publication-faults]
                  ["src/gate/archive_run.clj" "gate.archive-run-test" "actual-run-is-bracketed-and-all-archives-roundtrip" run-faults]])
         [{:id "growing-frame-revalidated" :source "src/gate/admission.cljc" :namespace "gate.admission-test"
           :control "canonical-and-human-edn-have-the-same-meaning"
           :anchor "(append-item frame value start)"
           :replacement "(do (when (= :vector (:kind frame)) (close-frame frame 93 start)) (append-item frame value (identity start)))"
           :test "instrumented-reader-does-not-rescan-growing-collections"}
          {:id "partition-checks-skipped" :source "src/gate/graph.cljc" :namespace "gate.measure-test"
           :control "serial-work-is-exact-and-does-not-claim-run-coverage"
           :anchor "(mapcat #(partition-findings lookup-measurement %) (:partitions graph))"
           :replacement "[]" :test "internally-contradictory-assertions-name-their-failure"}
          {:id "html-archive-binding-ignored" :source "src/gate/report.clj" :namespace "gate.archive-test"
           :control "summaries-bind-the-full-observed-tree"
           :anchor "(when metadata (archive/require-valid! (assoc metadata :graph (canonical/normalize-graph value))))"
           :replacement "nil" :test "html-binds-archive-and-escapes-hostile-labels"}
          {:id "repository-cancellation-ignored" :source "src/gate/repository.clj" :namespace "gate.repository-test"
           :control "strict-git-records" :anchor "(when @(:cancellation state) (git/refuse! :repository-cancelled))"
           :replacement "nil" :test "cancellation-stops-provenance-before-launch-and-between-byte-reads"}
          {:id "stream-checkpoints-removed" :source "src/gate/inputs.clj" :namespace "gate.repository-test"
           :control "strict-git-records" :anchor "(loop []\n          (checkpoint)\n          (let [n (.read channel buffer)]"
           :replacement "(loop []\n          (let [n (.read channel buffer)]"
           :test "cancellation-stops-provenance-before-launch-and-between-byte-reads"}])))

(defn -main
  "Assess every declared fault against one frozen source census and full before/after suites."
  [output-parent]
  (binding [campaign/*scope* :archive-delivery campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
