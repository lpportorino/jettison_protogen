(ns lvgl-codegen.schema-test
  "Guard tests for the leaf-widget content-sizing contract in
   `lvgl-codegen.schema/validate-screen-semantics` — the codegen-time check that
   fails a screen when a self-size-LESS leaf (lv_bar/lv_slider/lv_led) is
   content-sized and would silently collapse to ~0px.

   Hermetic: each test builds a screen map in-memory and calls
   `validate-screen-semantics` directly — no I/O, no fixtures, no sleep."
  (:require [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.schema :as schema]))

(set! *warn-on-reflection* true)

;; -- leaf-widget content-sizing guard: the self-size-LESS leaves collapse --
(deftest test-validate-semantics-leaf-content-sizing-flagged
  (testing "w-content on a self-size-less parts-widget (bar/led/arc/switch/spinner/
            scale/buttonmatrix) collapses to ~0px — no GET_SELF_SIZE handler, no
            child to measure, so a hard codegen error"
    (doseq [tag [:lv_bar :lv_led :lv_arc :lv_switch :lv_spinner :lv_scale
                 :lv_buttonmatrix :lv_chart]]
      (let [screen {:type :screen
                    :subjects {:bp {:type :int}}
                    :events {}
                    :tree {:tag tag :class "w-content h-12"}}
            errors (schema/validate-screen-semantics screen)]
        (is (some #(and (= :leaf-content-sizing (:type %)) (= :width (:dimension %)))
                  errors)
            (str tag " content-sizing its width is flagged"))))))

;; -- lv_image / lv_line DEFAULT to LV_SIZE_CONTENT and answer GET_SELF_SIZE from
;; their own content (image → source dims, line → points bbox), so content-sizing
;; them is their canonical mode, NOT a collapse. They were removed from the guard
;; (were 5 tags, now 3). RED before the narrowing (they WERE flagged); GREEN after.
(deftest test-validate-semantics-self-sizing-leaf-content-ok
  (testing "content-sizing lv_image (src dims) / lv_line (points bbox) is their
            canonical LVGL mode — not a leaf-sizing error"
    (doseq [tag [:lv_image :lv_line]]
      (let [screen {:type :screen
                    :subjects {:bp {:type :int}}
                    :events {}
                    :tree {:tag tag :class "w-content h-content"}}
            errors (schema/validate-screen-semantics screen)]
        (is (not (some #(= :leaf-content-sizing (:type %)) errors))
            (str tag " content-sizing is not a leaf-sizing error (self-size widget)"))))))

;; -- slider content-sizing: an lv_slider is a parts-widget (main/knob/indicator)
;; with no CHILD to measure, so it collapses under content-sizing exactly like
;; lv_bar (already guarded) — a `w-120 h-content` slider renders 120x0. Adding
;; :lv_slider to the leaf-sizing guard catches the regression. Red→green (RED
;; before the guard entry: a slider's height is not flagged; GREEN after).
(deftest test-validate-semantics-slider-content-sizing-flagged
  (testing "an lv_slider content-sizing its height is flagged — a parts-widget with
            no child to measure collapses to 0px, like lv_bar"
    (let [screen {:type :screen
                  :subjects {:bp {:type :int}}
                  :events {}
                  :tree {:tag :lv_slider :class "w-120 h-content"}}
          errors (schema/validate-screen-semantics screen)]
      (is (some #(and (= :leaf-content-sizing (:type %)) (= :height (:dimension %)))
                errors)
          "lv_slider height content-sizing is flagged (parity with lv_bar)"))))

(deftest test-validate-semantics-leaf-h-content-bp-prefixed-flagged
  (testing "a bp-prefixed md:h-content on a leaf still collapses it at that
            breakpoint and is flagged"
    (let [screen {:type :screen
                  :subjects {:bp {:type :int}}
                  :events {}
                  :tree {:tag :lv_bar :class "w-120 md:h-content"}}
          errors (schema/validate-screen-semantics screen)]
      (is (some #(and (= :leaf-content-sizing (:type %)) (= :height (:dimension %)))
                errors)))))

(deftest test-validate-semantics-leaf-explicit-size-ok
  (testing "explicitly-sized leaves pass; a CONTAINER may still content-size (it
            has children to measure) — the guard is scoped to childless leaves"
    (let [screen {:type :screen
                  :subjects {:bp {:type :int}}
                  :events {}
                  :tree {:tag :lv_obj
                         :class "w-content h-content"
                         :children [{:tag :lv_bar :class "w-120 h-12"}
                                    {:tag :lv_led :class "w-24 h-24"}]}}]
      (is (nil? (schema/validate-screen-semantics screen))
          "explicit-sized leaves under a content-sized container produce no error"))))
