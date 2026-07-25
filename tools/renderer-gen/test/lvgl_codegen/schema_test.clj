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
;; The assertion is deliberately TYPE-SPECIFIC: these nodes carry no content prop,
;; so they DO also raise :missing-required-content (the guard below). Keep the
;; negative scoped to :leaf-content-sizing — never widen it to `(nil? errors)`.
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

;; -- required-content guard: a widget whose ONLY content source is a STATIC
;; authored prop renders nothing at all when that prop is absent. An lv_image
;; without `image_props.src` and an lv_line without `line_props.points` are
;; permanently invisible nodes — a missing-CONTENT fault, distinct from the
;; leaf-sizing collapse above (which is about HOW a node is measured, not about
;; whether it has anything to draw).
(deftest test-validate-semantics-missing-required-content-flagged
  (testing "a sourceless lv_image / pointless lv_line is a hard codegen error"
    (doseq [[tag prop-path] {:lv_image [:image_props :src]
                             :lv_line [:line_props :points]}]
      (let [screen {:type :screen
                    :subjects {:bp {:type :int}}
                    :events {}
                    :tree {:tag tag :class "w-120 h-120"}}
            errors (schema/validate-screen-semantics screen)]
        (is (some #(and (= :missing-required-content (:type %)) (= tag (:widget %))
                        (= prop-path (:prop %)))
                  errors)
            (str tag " without " prop-path " is flagged"))))))

(deftest test-validate-semantics-empty-required-content-flagged
  (testing "an EMPTY content prop is absence — a \"\" src / [] points still flags"
    (doseq [[tag props] {:lv_image {:image_props {:src ""}}
                         :lv_line {:line_props {:points []}}}]
      (let [screen {:type :screen
                    :subjects {:bp {:type :int}}
                    :events {}
                    :tree (merge {:tag tag :class "w-120 h-120"} props)}
            errors (schema/validate-screen-semantics screen)]
        (is (some #(and (= :missing-required-content (:type %)) (= tag (:widget %)))
                  errors)
            (str tag " with an empty content prop is flagged"))))))

(deftest test-validate-semantics-scalar-required-content-flagged
  (testing "a SCALAR content prop is unusable content — flagged, never thrown"
    ;; The prop bags are typed bare `map?` (proto-IR passthrough), so an
    ;; authored `:src 42` clears validate-screen and reaches this guard, while
    ;; the tight `string?` shape that would reject it does not run until
    ;; validate-ir! several stages later. A bare `seq` throws here ("Don't know
    ;; how to create ISeq from: java.lang.Long") — a stack trace with neither
    ;; tag nor prop path, which is what the discriminating error map replaces.
    (doseq [[tag props] {:lv_image {:image_props {:src 42}}
                         :lv_line {:line_props {:points :nope}}}]
      (let [screen {:type :screen
                    :subjects {:bp {:type :int}}
                    :events {}
                    :tree (merge {:tag tag :class "w-120 h-120"} props)}
            errors (schema/validate-screen-semantics screen)]
        (is (some #(and (= :missing-required-content (:type %)) (= tag (:widget %)))
                  errors)
            (str tag " with a scalar content prop is flagged, not thrown"))))))

;; NON-VACUITY control for the guard above: it must be capable of PASSING, and it
;; must not reach beyond its two tags. Without this, the two assertions above
;; would also hold for a guard that flagged every node unconditionally.
(deftest test-validate-semantics-required-content-present-ok
  (testing "content-carrying lv_image / lv_line pass, and unrelated leaves
            (lv_led, lv_bar) are untouched by the content guard"
    (let [screen {:type :screen
                  :subjects {:bp {:type :int}}
                  :events {}
                  :tree {:tag :lv_obj
                         :children [{:tag :lv_image
                                     :class "w-24 h-24"
                                     :image_props {:src "P:icons/test_icon.svg"}}
                                    {:tag :lv_line
                                     :class "w-120 h-40"
                                     :line_props {:points [{:x 0 :y 0} {:x 50 :y 30}]}}
                                    {:tag :lv_led :class "w-24 h-24"}
                                    {:tag :lv_bar :class "w-120 h-12"}]}}]
      (is (nil? (schema/validate-screen-semantics screen))
          "populated image/line beside unrelated leaves produce no error at all"))))
