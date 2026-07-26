(ns lvgl-codegen.lvgl-parity-test
  "Guard tests for the DIRECT-CAST parity contract —
   `lvgl-codegen.construct.lift/direct-cast-parity-violations`, thrown on by
   `lvgl-codegen.construct.factory/emitted-enums`.

   The contract: for an enum `renderer.c` direct-casts, the assigned proto
   number and the LVGL header value are one integer wearing two names. Nothing
   in the C build can catch a divergence — both sides are plain ints and the
   generated header IS the declaration — so it would surface as a widget
   aligning or wrapping wrongly at runtime, arbitrarily far from the LVGL pin
   bump that caused it.

   Hermetic: every test builds enum constructs in-memory and calls the pure
   predicate directly. No clang, no extraction, no I/O — the LIVE extraction is
   gated separately by `make -f renderer.mk construct-bindings`.

   These tests exist to prove the guard FIRES. A parity assertion that has only
   ever been watched passing is indistinguishable from one that asserts
   nothing, so the negative cases below are the point and the clean case is the
   control."
  (:require [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.construct.lift :as lift]))

(set! *warn-on-reflection* true)

(defn- member
  "One enum member; `resolved-int` nil marks a proto-only synthetic."
  [c-name resolved-int proto-number]
  {:c-name c-name
   :resolved-int resolved-int
   :bitmask? false
   :clj-keyword (keyword (or c-name "synthetic"))
   :proto-name (or c-name "SYNTHETIC")
   :proto-number proto-number})

(defn- enum-construct
  "One enum typedef construct with the given cast class and members."
  [typedef-name cast-class members]
  {:kind :enum
   :typedef-name typedef-name
   :anon? true
   :cast-class cast-class
   :proto-type (str typedef-name "-proto")
   :members (vec members)})

(deftest direct-cast-agreement-is-clean
  (testing "a direct-cast enum whose numbers match the header reports nothing"
    (is (= [] (lift/direct-cast-parity-violations
               [(enum-construct "lv_align_t" :direct
                                [(member "LV_ALIGN_DEFAULT" 0 0)
                                 (member "LV_ALIGN_TOP_LEFT" 1 1)])])))))

(deftest direct-cast-divergence-is-caught
  (testing "a direct-cast member numbered differently from its header value is reported"
    (let [v (lift/direct-cast-parity-violations
             [(enum-construct "lv_align_t" :direct
                              [(member "LV_ALIGN_DEFAULT" 0 0)
                               ;; header says 1, registry assigned 7
                               (member "LV_ALIGN_TOP_LEFT" 1 7)])])]
      (is (= 1 (count v)))
      (is (= {:typedef "lv_align_t"
              :member "LV_ALIGN_TOP_LEFT"
              :proto-number 7
              :resolved-int 1}
             (first v)))))
  (testing "every diverging member is reported, not just the first"
    (is (= 2 (count (lift/direct-cast-parity-violations
                     [(enum-construct "lv_dir_t" :direct
                                      [(member "LV_DIR_LEFT" 1 4)
                                       (member "LV_DIR_RIGHT" 2 5)])]))))))

(deftest lut-enums-are-exempt
  (testing "a :lut enum may diverge — its generated table IS the indirection"
    (is (= [] (lift/direct-cast-parity-violations
               [(enum-construct "lv_flex_flow_t" :lut
                                [(member "LV_FLEX_FLOW_ROW" 0 1)
                                 (member "LV_FLEX_FLOW_COLUMN" 1 2)])])))))

(deftest synthetics-are-skipped-not-reported
  (testing "a proto-only synthetic has no LVGL value to agree with"
    (is (= [] (lift/direct-cast-parity-violations
               [(enum-construct "lv_flex_flow_t" :direct
                                [(member nil nil 0)
                                 (member "LV_FLEX_FLOW_ROW" 1 1)])]))))
  (testing "a synthetic does not mask a real divergence beside it"
    (is (= 1 (count (lift/direct-cast-parity-violations
                     [(enum-construct "lv_flex_flow_t" :direct
                                      [(member nil nil 0)
                                       (member "LV_FLEX_FLOW_ROW" 1 9)])]))))))

(deftest empty-input-reports-nothing-and-that-is-not-a-pass
  (testing "no constructs yields no violations — the vacuous case, named"
    ;; This predicate CANNOT defend itself against an empty or partial
    ;; extraction: with nothing to iterate it reports clean, which is a pass
    ;; value identical to its nothing-ran value. `missing-required-typedefs`
    ;; below is what covers that, upstream of here.
    (is (= [] (lift/direct-cast-parity-violations [])))))

(deftest incomplete-extraction-is-refused
  (testing "an extraction missing a required typedef is reported, sorted"
    (let [full (zipmap lift/required-typedefs (repeat [{:name "X" :value 0}]))]
      (testing "complete extraction reports nothing"
        (is (= [] (lift/missing-required-typedefs full))))
      (testing "one dropped typedef is named"
        (is (= ["lv_chart_axis_t"]
               (lift/missing-required-typedefs (dissoc full "lv_chart_axis_t")))))
      (testing "several dropped typedefs are all named, sorted"
        (is (= ["lv_arc_mode_t" "lv_dir_t"]
               (lift/missing-required-typedefs
                (dissoc full "lv_dir_t" "lv_arc_mode_t")))))))
  (testing "the EMPTY extraction — the case a shell -s test cannot see — is refused"
    ;; The extractor's final awk closes its map unconditionally, so a
    ;; zero-record run emits `}`: a well-formed, non-empty, contentless table.
    ;; Every required typedef is missing, so this is the loudest failure, not
    ;; the quietest.
    (is (= (vec (sort lift/required-typedefs))
           (lift/missing-required-typedefs {}))))
  (testing "extra typedefs beyond the required set are not an error"
    ;; A real extraction carries every LVGL enum, most of which the factory
    ;; never emits; only ABSENCE of a required one matters.
    (is (= [] (lift/missing-required-typedefs
               (assoc (zipmap lift/required-typedefs (repeat [{:name "X" :value 0}]))
                      "lv_something_unused_t" [{:name "Y" :value 1}]))))))
