(ns gate.paired-performance-simulation
  "Seeded Monte-Carlo of the paired verdict: how often it qualifies a direction as blocks grow.
   Each scenario draws independent lognormal noise on every comparison and A/A ratio around a
   true median, builds documents with the test fixture, and counts evaluate's verdicts. The
   numbers measure THIS predicate under THESE generated conditions; they are not a claim about
   any real benchmark's noise."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [gate.paired-performance :as paired]
            [gate.paired-performance-test :as fixture]
            [malli.core :as m])
  (:import [java.util Random]))

(def Scenario
  [:map {:closed true} [:id :keyword] [:comparison-median [:double {:min 0.1 :max 10.0}]]
   [:control-median [:double {:min 0.1 :max 10.0}]] [:sigma [:double {:min 0.0 :max 1.0}]]])

(def scenarios
  "Two true effects beyond the 10 % floor; no effect; and the worst nulls, a median exactly on either floor."
  [{:id :effect-0.85 :comparison-median 0.85 :control-median 1.0 :sigma 0.03}
   {:id :effect-0.88 :comparison-median 0.88 :control-median 1.0 :sigma 0.03}
   {:id :no-effect :comparison-median 1.0 :control-median 1.0 :sigma 0.03}
   {:id :null-at-faster-floor :comparison-median (/ 1.0 1.1) :control-median 1.0 :sigma 0.03}
   {:id :null-at-slower-floor :comparison-median 1.1 :control-median 1.0 :sigma 0.03}])

(defn- draw
  "One positive integer nanosecond count for a 1000000 ns reference at the given median ratio."
  [^Random random median sigma]
  (str (max 1 (Math/round (* 1000000.0 median (Math/exp (* sigma (.nextGaussian random))))))))
(m/=> draw [:=> [:cat [:fn #(instance? Random %)] :double :double] [:string {:min 1}]])

(defn- verdict
  "Evaluate one generated document of the given block count."
  [random scenario blocks policy]
  (let [{:keys [comparison-median control-median sigma]} scenario
        document (-> (fixture/fixture-of (vec (repeatedly blocks #(draw random comparison-median sigma)))
                                         (vec (repeatedly blocks #(draw random control-median sigma))))
                     (assoc-in [:protocol :policy] policy) fixture/reseal)
        result (paired/evaluate document)]
    (if (= :qualified (:status result)) (:direction result) (:status result))))
(m/=> verdict [:=> [:cat [:fn #(instance? Random %)] Scenario [:int {:min 2 :max 32}] :map] :keyword])

(defn rates
  "Count verdicts per scenario and block count over a fixed seed; the seed makes reruns identical."
  [{:keys [seed trials blocks policy]}]
  (let [random (Random. (long seed))]
    (vec (for [scenario scenarios n blocks]
           (let [counts (frequencies (repeatedly trials #(verdict random scenario n policy)))]
             {:scenario (:id scenario) :blocks n :trials trials
              :faster (get counts :candidate-faster 0) :slower (get counts :candidate-slower 0)})))))
(m/=> rates [:=> [:cat :map] [:vector :map]])

(defn -main
  "Print the qualification table for the fixture policy, optionally overridden by an EDN map argument."
  [& [overrides]]
  (let [options (merge {:seed 20261015 :trials 1000 :blocks [6 8 10 12 16 20 24 32]
                        :policy (:policy fixture/protocol)}
                       (when overrides (edn/read-string overrides)))]
    (println (str "seed=" (:seed options) " trials=" (:trials options) " policy=" (pr-str (:policy options))))
    (println "| scenario | blocks | qualified faster | qualified slower |")
    (println "|---|---|---|---|")
    (doseq [{:keys [scenario blocks trials faster slower]} (rates options)]
      (println (str/join " | " ["" (name scenario) blocks (format "%.3f" (/ faster (double trials)))
                                (format "%.3f" (/ slower (double trials))) ""])))
    (shutdown-agents)))
(m/=> -main [:=> [:cat [:? :string]] :nil])
