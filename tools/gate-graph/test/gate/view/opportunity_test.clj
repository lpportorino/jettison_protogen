(ns gate.view.opportunity-test
  "Candidates require sustained changes and contiguous evidence, never guessed idle capacity."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission]
            [gate.canonical]
            [gate.fixtures :as fixtures]
            [gate.view.opportunity :as opportunity]
            [malli.core]))

(defn samples
  "Build a uniform-duration synthetic CPU series from exact values."
  [values]
  (mapv (fn [i value] (assoc (fixtures/measurement) :id (str "sample-" i) :value (str value)
                             :interval {:start-ns (str (* i 10)) :end-ns (str (* (inc i) 10))})) (range) values))

(deftest sustained-dip-not-causal-blame
  (let [series (samples (concat (repeat 20 80) [10 10 10]))
        candidates (opportunity/detect series)]
    (is (= 1 (count candidates)))
    (is (= :activity-dip (:kind (first candidates))))
    (is (= {:start-ns "200" :end-ns "230"} (:interval (first candidates))))
    (is (= 20 (count (:baseline (first candidates)))))
    (is (empty? (opportunity/detect (assoc-in series [19 :status] :partial))))
    (is (empty? (opportunity/detect (assoc-in series [20 :interval :start-ns] "201"))))
    (is (empty? (opportunity/detect (subvec series 1))))))

(deftest activity-burst-and-counter-refusal
  (let [series (samples (concat (repeat 20 10) [80 80 80]))]
    (is (= :activity-burst (:kind (first (opportunity/detect series)))))
    (is (nil? (opportunity/value (assoc (first series) :form :counter))))
    (is (empty? (opportunity/detect (samples (repeat 30 10)))))
    (is (empty? (opportunity/detect (samples (concat (repeat 20 0) [80 80 80])))))))

(deftest metric-families-do-not-mislabel-pressure-or-errors
  (let [series (samples (concat (repeat 20 10) [80 80 80]))]
    (doseq [quantity [:pressure-io-some-us :assertions-failed :assertions-errored]]
      (is (empty? (opportunity/detect (mapv #(assoc % :quantity quantity) series)))))
    (is (= :footprint-rise (:kind (first (opportunity/detect (mapv #(assoc % :quantity :memory-current-bytes :form :gauge) series))))))
    (is (= :activity-change-v1 (:policy (first (opportunity/detect series)))))))

(def ^:private raw-detect
  "Capture the production boundary before the suite installs instrumentation."
  opportunity/detect)

(deftest production-bound-and-canonical-candidates
  (let [values (samples (concat (repeat 20 80) [10 10 10]))
        candidates (opportunity/detect values)
        code (fn [f] (try (f) (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    (is (= :invalid-opportunity-samples (code #(raw-detect (samples (repeat 513 80))))))
    (is (= :invalid-opportunity-samples (code #(raw-detect [{:unexpected true}]))))
    (doseq [value [candidates (first candidates) [] {:code :invalid-opportunity-samples}]]
      (let [encoded (gate.canonical/encode value 65536)]
        (is (= value (gate.admission/decode encoded :value gate.admission/default-limits)))
        (is (<= (gate.canonical/utf8-size encoded) 65536))))
    (is (false? (malli.core/validate opportunity/Candidate (assoc (first candidates) :policy :guessed))))
    (is (false? (malli.core/validate opportunity/Candidate (assoc (first candidates) :kind :bottleneck))))
    (is (every? (set (map :id values)) (concat (:baseline (first candidates)) (:observations (first candidates)))))))

(deftest point-gauges-and-variable-duration-rates
  (let [values (samples (concat (repeat 20 80) [10 10 10]))
        point-gauges (mapv #(-> % (assoc :form :gauge :quantity :memory-current-bytes)
                                (assoc-in [:interval :end-ns] (get-in % [:interval :start-ns]))) values)]
    (is (empty? (opportunity/detect point-gauges)))
    (is (= (opportunity/detect values) (opportunity/detect (vec (reverse values)))))
    (is (= 1.0 (opportunity/value (assoc (first values) :value "100" :interval {:start-ns "0" :end-ns "100"}))))
    (is (= 2.0 (opportunity/value (assoc (first values) :value "100" :interval {:start-ns "0" :end-ns "50"}))))
    (is (empty? (opportunity/detect (assoc-in values [19 :accounting] :exclusive))))))
