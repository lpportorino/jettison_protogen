(ns devcards.geometry-test
  "Off-by-one canaries for `devcards.geometry`.

   Dump coords are INCLUSIVE on both ends (lv_obj_get_coords fills an
   lv_area_t whose x2/y2 is the last pixel INSIDE the box), so every
   predicate here is one pixel away from being wrong in a way that reads
   as correct: an exclusive reading makes abutting boxes 'overlap' and
   turns the whole overlap lane into false findings. The adjacency cases
   below are what pin the convention."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.geometry :as geometry]))

(deftest abutting-boxes-do-not-overlap
  (testing "a 10px box at the origin is [0 0 9 9]; the box starting at x=10
            touches it with zero clear pixels and shares none"
    (is (= 0 (geometry/separation [0 0 9 9] [10 0 19 9])))
    (is (not (geometry/intersect? [0 0 9 9] [10 0 19 9])))))

(deftest one-shared-pixel-column-is-an-overlap
  (is (geometry/intersect? [0 0 9 9] [9 0 19 9]))
  (is (= -1 (geometry/separation [0 0 9 9] [9 0 19 9]))))

(deftest separation-counts-clear-pixels
  (testing "columns 10 and 11 are clear between the two boxes"
    (is (= 2 (geometry/separation [0 0 9 9] [12 0 19 9])))))

(deftest separation-is-symmetric
  (is (= (geometry/separation [0 0 9 9] [12 0 19 9])
         (geometry/separation [12 0 19 9] [0 0 9 9]))))

(deftest a-gap-on-either-axis-separates
  (testing "boxes overlapping horizontally but stacked vertically are apart —
            taking the MAX of the axes is what makes this a separation
            rather than a distance"
    (is (= 5 (geometry/separation [0 0 99 9] [0 15 99 24])))
    (is (not (geometry/intersect? [0 0 99 9] [0 15 99 24])))))

(deftest containment-is-a-deep-overlap
  (testing "a box fully inside another reports the shallower penetration"
    (is (geometry/intersect? [0 0 99 99] [10 10 19 19]))
    (is (neg? (geometry/separation [0 0 99 99] [10 10 19 19])))))

(deftest too-close-spans-the-strictness-range
  (let [abutting [[0 0 9 9] [10 0 19 9]]
        overlapping [[0 0 9 9] [9 0 19 9]]
        apart [[0 0 9 9] [12 0 19 9]]]
    (testing "gap 0 is the strict-overlap rule: only a shared pixel fires"
      (is (geometry/too-close? (first overlapping) (second overlapping) 0))
      (is (not (geometry/too-close? (first abutting) (second abutting) 0))))
    (testing "gap 1 additionally catches boxes that touch"
      (is (geometry/too-close? (first abutting) (second abutting) 1))
      (is (not (geometry/too-close? (first apart) (second apart) 1))))
    (testing "gap 3 demands three clear pixels, so a 2px gap fires"
      (is (geometry/too-close? (first apart) (second apart) 3)))))

(deftest malformed-coords-throw
  (testing "a node with absent or short coords must never compare as 'far
            apart' — that is the failure where a broken run and a clean run
            produce byte-identical output"
    (is (thrown? Exception (geometry/separation nil [0 0 9 9])))
    (is (thrown? Exception (geometry/separation [0 0 9] [0 0 9 9])))
    (is (thrown? Exception (geometry/separation [0 0 9 "9"] [0 0 9 9])))))

(deftest describe-reports-inclusive-dimensions
  (is (= "[0,0,9,9] 10x10" (geometry/describe [0 0 9 9]))))
