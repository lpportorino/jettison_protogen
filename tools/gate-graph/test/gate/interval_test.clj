(ns gate.interval-test
  "Occupied time is tested against discrete point-set coverage, not the merge code."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.interval :as interval]))

(deftest overlaps-and-gaps-retain-distinct-quantities
  (is (= {:count 3 :start-ns "0" :end-ns "30" :occupied-ns "25"
          :envelope-ns "30" :duration-sum-ns "30"}
         (interval/summarize [{:start-ns "0" :end-ns "10"}
                              {:start-ns "5" :end-ns "20"}
                              {:start-ns "25" :end-ns "30"}]))))

(deftest zero-and-empty-intervals
  (is (= {:count 0 :start-ns nil :end-ns nil :occupied-ns "0"
          :envelope-ns "0" :duration-sum-ns "0"} (interval/summarize [])))
  (is (= "0" (:occupied-ns (interval/summarize [{:start-ns "7" :end-ns "7"}])))))

(deftest disjointness-distinguishes-adjacent-work-from-coincident-instants
  (let [a {:start-ns "0" :end-ns "10"} b {:start-ns "10" :end-ns "20"}
        instant {:start-ns "10" :end-ns "10"}]
    (is (interval/separated? a b))
    (is (interval/separated? b a))
    (is (false? (interval/separated? instant instant)))
    (is (false? (interval/separated? a instant)))
    (is (interval/separated? {:start-ns "0" :end-ns "9"} instant))
    (is (= :invalid-interval
           (try (interval/separated? {:start-ns "10" :end-ns "9"} b)
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))

(deftest union-agrees-with-independent-discrete-coverage
  (is (:pass?
       (tc/quick-check
        300
        (prop/for-all [pairs (gen/vector (gen/tuple (gen/choose 0 50) (gen/choose 0 50)) 0 40)]
                      (let [ranges (map (fn [[a b]] [(min a b) (max a b)]) pairs)
                            occupied (set (mapcat (fn [[a b]] (range a b)) ranges))
                            intervals (mapv (fn [[a b]] {:start-ns (str a) :end-ns (str b)}) ranges)]
                        (= (str (count occupied)) (:occupied-ns (interval/summarize intervals)))))
        :seed 271828))))
