(ns gate.view.opportunity
  "Explainable activity-change candidates. These are investigation prompts, never causal or savings estimates."
  (:require [gate.contract :as c]
            [gate.decimal :as d]
            [malli.core :as m]))

(def Samples [:vector {:max 512} c/Measurement])
(def Candidate
  [:map {:closed true} [:policy [:= :activity-change-v1]] [:kind [:enum :activity-dip :activity-burst :footprint-drop :footprint-rise]]
   [:interval c/Interval] [:resource c/Id] [:quantity c/Quantity]
   [:baseline [:vector {:min 20 :max 20} c/Id]] [:observations [:vector {:min 3 :max 3} c/Id]]])
(def Candidates [:vector {:max 164} Candidate])
(def Failure [:map {:closed true} [:code [:= :invalid-opportunity-samples]]])
(def ^:private SamplesSchema (m/schema Samples))

(defn value
  "Approximate display/heuristic magnitude only. Deltas use their actual covered interval; gauges stay samples.
   Nil means unavailable, uncertain, counter, peak or zero-duration rate; no missing value becomes zero."
  [sample]
  (when (and (= :measured (:status sample)) (:value sample) (contains? #{:delta :gauge} (:form sample)))
    (let [number #?(:clj #(Double/parseDouble %) :cljs js/Number)
          length (d/subtract (get-in sample [:interval :end-ns]) (get-in sample [:interval :start-ns]))]
      (if (= :gauge (:form sample)) (number (:value sample))
          (when-not (= length "0") (/ (number (:value sample)) (number length)))))))
(m/=> value [:=> [:cat c/Measurement] [:maybe number?]])

(defn- continuous?
  "Require a contiguous, measured series with one source, scope, form, method and accounting definition."
  [samples]
  (and (contains? #{:cpu-user-ns :cpu-system-ns :bytes-read :bytes-written :gpu-utilization-percent :memory-current-bytes} (:quantity (first samples)))
       (every? #(some? (value %)) samples)
       (= 1 (count (set (map #(select-keys % [:resource :quantity :node :source :form :method :accounting]) samples))))
       (every? (fn [[a b]] (= (get-in a [:interval :end-ns]) (get-in b [:interval :start-ns]))) (partition 2 1 samples))))
(m/=> continuous? [:=> [:cat Samples] :boolean])

(defn detect
  "Inspect at most 512 time-ordered samples, returning non-overlapping three-observation candidates.
   Rule v1: preceding 20 contiguous measured observations, median > 0, then three values all below
   50% or above 200% of that median (mean of middle two, observation-weighted). Memory gauges describe
   footprint; GPU gauges describe activity. Pressure and outcome counters are excluded.
   Point gauges with separated sample times have no contiguous coverage and produce no candidates.
   No interpolation, resampling, clock join, capacity inference or recoverable-time estimate is made.
   Caller groups series by resource/quantity and discloses any input truncation. Ordering uses exact time."
  [samples]
  (when-not (m/validate SamplesSchema samples)
    (throw (ex-info "Expected at most 512 admitted measurement samples" {:code :invalid-opportunity-samples})))
  (let [samples (vec (sort #(let [order (d/compare (get-in %1 [:interval :start-ns]) (get-in %2 [:interval :start-ns]))]
                              (if (zero? order) (compare (:id %1) (:id %2)) order)) samples))]
    (loop [i 20 found []]
      (if (> (+ i 3) (count samples)) found
          (let [baseline (subvec samples (- i 20) i) observed (subvec samples i (+ i 3))
                complete (into baseline observed)
                median (when (continuous? complete) (let [values (vec (sort (map value baseline)))] (/ (+ (nth values 9) (nth values 10)) 2)))
                direction (when (and median (pos? median))
                            (cond (every? #(< (value %) (* median 0.5)) observed) :dip
                                  (every? #(> (value %) (* median 2)) observed) :burst))]
            (if direction
              (let [sample (first observed) footprint? (= :memory-current-bytes (:quantity sample))]
                (recur (+ i 3) (conj found {:policy :activity-change-v1 :kind (if footprint? (if (= :dip direction) :footprint-drop :footprint-rise)
                                                                                  (if (= :dip direction) :activity-dip :activity-burst))
                                            :resource (:resource sample) :quantity (:quantity sample)
                                            :interval {:start-ns (get-in sample [:interval :start-ns]) :end-ns (get-in (peek observed) [:interval :end-ns])}
                                            :baseline (mapv :id baseline) :observations (mapv :id observed)})))
              (recur (inc i) found)))))))
(m/=> detect [:=> [:cat Samples] Candidates])
