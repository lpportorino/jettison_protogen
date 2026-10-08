(ns gate.view.campaign
  "Attributed view-evidence faults; compile errors, crashes and timeouts never count as kills."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def model-faults
  [{:id "fold-occupied-union-skipped"
    :anchor ":occupied (interval/union intervals)"
    :replacement ":occupied intervals"
    :test "fold-keeps-gaps-and-overlap"
    :control "point-window-boundaries"}
   {:id "right-window-boundary-included"
    :anchor "(neg? (d/compare (:start-ns a) (:end-ns b)))"
    :replacement "(not (pos? (d/compare (:start-ns a) (:end-ns b))))"
    :test "point-window-boundaries"
    :control "fold-keeps-gaps-and-overlap"}
   {:id "incident-edge-needs-both-members"
    :anchor "(or (contains? members (get-in row [:from :node])) (contains? members (get-in row [:to :node])))"
    :replacement "(and (contains? members (get-in row [:from :node])) (contains? members (get-in row [:to :node])))"
    :test "bounded-pages-conserve-typed-edges"
    :control "fold-keeps-gaps-and-overlap"}
   {:id "foreign-artifact-cursor-accepted"
    :anchor "(not= artifact (:artifact cursor))"
    :replacement "false"
    :test "rejected-members-and-stale-continuations"
    :control "neighborhoods-return-nodes-not-edges"}])

(def opportunity-faults
  [{:id "partial-sample-treated-as-measured"
    :anchor "(= :measured (:status sample))"
    :replacement "(contains? #{:measured :partial} (:status sample))"
    :test "sustained-dip-not-causal-blame"
    :control "activity-burst-and-counter-refusal"}
   {:id "sampling-gap-ignored"
    :anchor "(= (get-in a [:interval :end-ns]) (get-in b [:interval :start-ns]))"
    :replacement "true"
    :test "sustained-dip-not-causal-blame"
    :control "activity-burst-and-counter-refusal"}
   {:id "counter-treated-as-rate"
    :anchor "(contains? #{:delta :gauge} (:form sample))"
    :replacement "(contains? #{:delta :gauge :counter} (:form sample))"
    :test "activity-burst-and-counter-refusal"
    :control "metric-families-do-not-mislabel-pressure-or-errors"}
   {:id "zero-baseline-ratio-accepted"
    :anchor "(and median (pos? median))"
    :replacement "(some? median)"
    :test "activity-burst-and-counter-refusal"
    :control "sustained-dip-not-causal-blame"}
   {:id "pressure-mislabeled-as-activity"
    :anchor "#{:cpu-user-ns :cpu-system-ns :bytes-read :bytes-written :gpu-utilization-percent :memory-current-bytes}"
    :replacement "#{:cpu-user-ns :cpu-system-ns :bytes-read :bytes-written :gpu-utilization-percent :memory-current-bytes :pressure-io-some-us}"
    :test "metric-families-do-not-mislabel-pressure-or-errors"
    :control "sustained-dip-not-causal-blame"}
   {:id "memory-change-mislabeled-as-activity"
    :anchor "(= :memory-current-bytes (:quantity sample))"
    :replacement "false"
    :test "metric-families-do-not-mislabel-pressure-or-errors"
    :control "sustained-dip-not-causal-blame"}])

(def faults
  (vec (concat
        (map #(assoc % :source "src/gate/view/model.cljc" :namespace "gate.view.model-test") model-faults)
        (map #(assoc % :source "src/gate/view/opportunity.cljc" :namespace "gate.view.opportunity-test") opportunity-faults))))

(defn -main
  "Freeze the module, pass full before/after suites and require a named assertion kill plus clean control per fault.
   OUTPUT must be an ignored owned scratch parent. Every worker uses the existing 180-second bound."
  [output-parent]
  (binding [campaign/*scope* :viewer-delivery campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
