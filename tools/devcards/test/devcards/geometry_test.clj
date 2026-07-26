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

(deftest intersection-returns-the-shared-box
  (testing "inclusive on both ends, so the shared box includes its own edges"
    (is (= [5 5 9 9] (geometry/intersection [0 0 9 9] [5 5 14 14])))
    (is (= [0 0 9 9] (geometry/intersection [0 0 9 9] [0 0 9 9])))
    (testing "containment yields the inner box, not the outer"
      (is (= [2 2 4 4] (geometry/intersection [0 0 9 9] [2 2 4 4]))))
    (testing "one shared pixel is a real intersection"
      (is (= [9 9 9 9] (geometry/intersection [0 0 9 9] [9 9 19 19]))))))

(deftest intersection-is-nil-when-nothing-is-shared
  (testing "nil is the empty result, never a degenerate box: on INCLUSIVE
            coords an empty region cannot be spelled as a rect, and an
            inverted vector would flow on into arithmetic that returns a
            confident wrong number"
    (is (nil? (geometry/intersection [0 0 9 9] [20 20 29 29])))
    (testing "ABUTTING boxes share no pixel — the off-by-one this whole ns
              is sensitive to. [0 0 9 9] and [10 0 19 9] touch with nothing
              between, so their intersection is empty, not a zero-width box"
      (is (nil? (geometry/intersection [0 0 9 9] [10 0 19 9])))
      (is (= 0 (geometry/separation [0 0 9 9] [10 0 19 9]))))
    (testing "and it is empty when only ONE axis misses"
      (is (nil? (geometry/intersection [0 0 9 9] [0 20 9 29]))))))

(deftest intersection-rejects-malformed-boxes
  (testing "same contract as separation — an unmeasurable box must throw
            rather than clip to something plausible.

            Asserting the MESSAGE, not merely that something throws: nil and
            a 3-element vector both blow up inside max/min with a
            NullPointerException anyway, so `(thrown? Exception …)` passes
            with the check-box! guards DELETED. A canary that cannot tell
            the guard from its absence is not guarding anything."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed coords in intersection/a"
                          (geometry/intersection nil [0 0 9 9])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed coords in intersection/a"
                          (geometry/intersection [0 0 9] [0 0 9 9])))
    (testing "and the SECOND argument is guarded too, named as such — a
              guard on only one side reads identical on these inputs"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed coords in intersection/b"
                            (geometry/intersection [0 0 9 9] nil)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed coords in intersection/b"
                            (geometry/intersection [0 0 9 9] [0 0 9 "9"]))))))

(deftest intersection-agrees-with-intersect?
  (testing "two answers to the same question must never disagree — a box
            that intersects has a non-nil intersection, and one that does
            not has nil.

            The interesting inputs are the DEGENERATE ones. A well-formed
            pair agrees by construction, so a canary made only of those
            cannot fail; the cases below include zero-extent rects (x1=x2),
            which LVGL itself produces for a collapsed widget, and rects
            that touch on exactly one axis."
    (doseq [[a b] [[[0 0 9 9] [5 5 14 14]]
                   [[0 0 9 9] [10 0 19 9]]
                   [[0 0 9 9] [9 9 19 19]]
                   [[0 0 9 9] [20 20 29 29]]
                   [[0 0 9 9] [2 2 4 4]]
                   ;; zero-extent: a 1px-wide box, and a fully collapsed one
                   [[5 5 5 5] [5 5 5 5]]
                   [[5 5 5 5] [0 0 9 9]]
                   [[5 5 5 5] [6 6 9 9]]
                   [[5 5 5 9] [5 0 5 4]]
                   ;; sharing exactly one corner pixel
                   [[0 0 5 5] [5 5 9 9]]
                   ;; one axis overlaps, the other misses by one
                   [[0 0 9 9] [0 10 9 19]]
                   [[0 0 9 9] [10 0 19 9]]]]
      (is (= (geometry/intersect? a b)
             (some? (geometry/intersection a b)))
          (str "disagreement on " a " vs " b)))))
