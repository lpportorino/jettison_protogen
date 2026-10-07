(ns gate.live-campaign
  "Attributed real-filesystem and cache lifecycle fault assessment in disposable source copies."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def input-faults
  [{:id "byte-budget"
    :anchor "(when (> (get (swap! budget update kind + amount) kind) (get limits kind))"
    :replacement "(when (and (not= kind :bytes) (> (get (swap! budget update kind + amount) kind) (get limits kind)))"
    :test "file-byte-entry-and-depth-budgets-refuse-instead-of-truncating"}
   {:id "stale-content-digest"
    :anchor "(.formatHex (HexFormat/of) (.digest digest))"
    :replacement "(apply str (repeat 64 \"0\"))"
    :test "content-is-rehashed-even-when-size-and-mtime-are-restored"}
   {:id "membership-rescan"
    :anchor "(when-not (= membership after) (refuse! :unstable-input))"
    :replacement "nil"
    :test "membership-is-checked-again-after-reading-file-bytes"}
   {:id "required-input-floor"
    :anchor "(if required? (refuse! :missing-input) [])"
    :replacement "[]"
    :test "optional-absence-remains-membership-and-required-absence-refuses"}
   {:id "exclusion-pruning"
    :anchor "(excluded? exclusions relative) (recur (pop pending) result)"
    :replacement "false (recur (pop pending) result)"
    :test "globs-exclusions-and-unrelated-files-have-explicit-semantics"}])

(def attempt-faults
  [{:id "forced-run-ignored"
    :anchor "[origin (System/nanoTime)"
    :replacement "[force? false origin (System/nanoTime)"
    :test "cold-pass-then-real-hit-unrelated-hit-changed-miss-and-forced-run"}
   {:id "publication-without-isolation"
    :anchor "(not isolation-verified?) {:status :refused :reason :unproven-isolation}"
    :replacement "false {:status :refused :reason :unproven-isolation}"
    :test "mutable-workspace-can-be-observed-but-cannot-earn-or-use-receipts"}
   {:id "pre-run-snapshot-laundered"
    :anchor "(cache/admit gate before after dependencies after-deps"
    :replacement "(cache/admit gate after after dependencies after-deps"
    :test "input-changes-during-success-refuse-publication-with-live-before-after-evidence"}
   {:id "dependency-change-hidden"
    :anchor "(cache/admit gate before after dependencies after-deps"
    :replacement "(cache/admit gate before after dependencies dependencies"
    :test "dependency-result-change-during-work-prevents-publication"}])

(defn -main
  "Require clean controls, full baselines and exact landed bytes for each selected fault."
  [output-parent]
  (binding [campaign/*scope* :live-inputs
            campaign/*mutation-source* "src/gate/inputs.clj"
            campaign/*test-namespace* "gate.inputs-test"
            campaign/*control* "missing-toolchain-or-environment-is-not-complete-evidence"
            campaign/*faults* input-faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :cache-attempt
            campaign/*mutation-source* "src/gate/attempt.clj"
            campaign/*test-namespace* "gate.attempt-test"
            campaign/*control* "failing-assertions-and-vacuous-work-do-not-create-cache-evidence"
            campaign/*faults* attempt-faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
