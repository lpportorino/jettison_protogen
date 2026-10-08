(ns gate.api-campaign
  "Manual API identity/adoption faults with full baselines and independent neighboring controls."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def faults
  [{:id "canonical-budget-error-leaks"
    :anchor "(= :encoded-byte-limit (:code (ex-data error)))" :replacement "false"
    :test "legal-large-inventory-budget-errors-remain-closed"}
   {:id "duplicate-export-identities-accepted"
    :anchor "(when-not (= (count names) (count (set names))) (refuse! :api-duplicates phase))"
    :replacement "nil" :test "manifests-refuse-duplicate-and-forged-inventories"}
   {:id "manifest-tampering-accepted"
    :anchor "(= (:artifact expected) (:artifact document))" :replacement "true"
    :test "manifests-refuse-duplicate-and-forged-inventories"}
   {:id "upstream-declaration-version-skipped"
    :anchor "(not= (:entries before) (:entries after))" :replacement "false"
    :test "every-public-declaration-change-needs-upstream-version"}
   {:id "upstream-version-regression-accepted"
    :anchor "(< (:api-version after) (:api-version before))" :replacement "false"
    :test "every-public-declaration-change-needs-upstream-version"}
   {:id "new-tools-need-no-decision"
    :anchor "(= tools (set (map :id decisions)))" :replacement "true"
    :test "missing-tool-decisions-and-invented-wrappers-refuse"}
   {:id "invented-wrapper-accepted"
    :anchor "(= (set (:exports record)) wrappers)" :replacement "true"
    :test "missing-tool-decisions-and-invented-wrappers-refuse"}
   {:id "stale-manifest-acknowledged"
    :anchor "(= (:artifact document) (:manifest record))" :replacement "true"
    :test "independently-observed-evidence-cannot-be-acknowledged-away"}
   {:id "source-drift-ignored"
    :anchor "(= surface (:surface record))" :replacement "true"
    :test "independently-observed-evidence-cannot-be-acknowledged-away"}
   {:id "actual-export-drift-ignored"
    :anchor "(= (vec (sort exports)) (:exports record))" :replacement "true"
    :test "independently-observed-evidence-cannot-be-acknowledged-away"}
   {:id "decision-change-needs-no-ack"
    :anchor "(not (or (> api old-api) (> ack old-ack)))" :replacement "false"
    :test "decision-change-requires-ack-even-with-unchanged-wrapper-inventory"}
   {:id "exports-change-without-api-version"
    :anchor "(<= api old-api)" :replacement "false"
    :test "consumer-acknowledgement-and-export-version-are-independent"}
   {:id "consumer-version-regression-accepted"
    :anchor "(or (< api old-api) (< ack old-ack))" :replacement "false"
    :test "consumer-acknowledgement-and-export-version-are-independent"}])

(defn -main
  "Run selected API faults in copied frozen trees; require attributed assertions and passing controls."
  [output-parent]
  (binding [campaign/*scope* :api-adoption campaign/*mutation-source* "src/gate/api.cljc"
            campaign/*test-namespace* "gate.api-test"
            campaign/*control* "deterministic-identity-and-explicit-tool-adoption"
            campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
