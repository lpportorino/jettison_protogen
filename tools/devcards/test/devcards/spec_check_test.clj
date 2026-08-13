(ns devcards.spec-check-test
  "Unit tests for `devcards.spec-check` — the pure spec validators — the checks
   `load-spec` throws on, driven over hand-built maps with no file and no IO.

   WHY A PURE SEAM AT ALL. `load-spec` reads a file-level path, so a check
   living inside it can only be exercised by planting a spec on disk. Extracting
   the predicate is what makes it drivable, and is the same shape
   `devcards.corpus`'s refusals already expose."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.spec-check :as spec-check]))

(defn- widget
  "A widget block declaring `declared` cards and actually holding `n` of them."
  [tag declared n]
  (cond-> {:tag tag :cards (vec (for [i (range n)] {:id (str tag "/card-" i)}))}
    declared (assoc :authored-count declared)))

(deftest authored-count-problems-test
  (testing "an honest spec yields no problems"
    (is (empty? (spec-check/authored-count-problems
                 {:widgets [(widget "lv_obj" 2 2) (widget "lv_led" 3 3)]}))))

  (testing "a widget declaring FEWER cards than it holds is named, with both numbers"
    (let [ps (spec-check/authored-count-problems
              {:widgets [(widget "lv_obj" 5 7)]})]
      (is (= 1 (count ps)))
      (is (= {:widget "lv_obj" :authored-count 5 :actual 7} (first ps)))))

  (testing "a widget declaring MORE cards than it holds is named too"
    (let [ps (spec-check/authored-count-problems
              {:widgets [(widget "lv_host_proxy" 7 5)]})]
      (is (= 1 (count ps)))
      (is (= {:widget "lv_host_proxy" :authored-count 7 :actual 5} (first ps)))))

  (testing "OPPOSITE-DIRECTION errors are BOTH reported — the case :card-count cannot see"
    ;; :card-count sums every widget's cards, so an under and an over of equal
    ;; size cancel and that check passes over a spec that is wrong twice. This
    ;; is the whole reason the check is per-widget, so it is pinned here.
    (let [spec {:widgets [(widget "under" 5 7) (widget "over" 7 5)]}
          ps (spec-check/authored-count-problems spec)
          total-declared (reduce + (map :authored-count (:widgets spec)))
          total-actual (reduce + (map (comp count :cards) (:widgets spec)))]
      (is (= total-declared total-actual)
          "the corpus TOTAL agrees — which is exactly why a total cannot catch this")
      (is (= 2 (count ps)))
      (is (= #{"under" "over"} (set (map :widget ps))))))

  (testing "a widget with NO :authored-count is not a problem"
    ;; the key is optional; demanding it would report an omission as the same
    ;; fatal error as a wrong count, which is a separate decision
    (is (empty? (spec-check/authored-count-problems
                 {:widgets [(widget "lv_bar" nil 4)]}))))

  (testing "a widget with zero cards and a zero declaration agrees"
    (is (empty? (spec-check/authored-count-problems
                 {:widgets [(widget "empty" 0 0)]}))))

  (testing "an empty widget list yields no problems and does not throw"
    (is (empty? (spec-check/authored-count-problems {:widgets []})))))
