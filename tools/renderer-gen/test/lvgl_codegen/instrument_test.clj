(ns lvgl-codegen.instrument-test
  "THE CANARY for the arming seam — it proves instrumentation is LIVE in this run.

  WHY A GATE NEEDS THIS. Once `m/=>` specs are instrumented they ARE a gate, and
  `.claude/rules/gate-enforcement.md` §2 makes a constructed known-bad input that
  the gate REJECTS a condition of the gate existing. Without it, arming can silently
  stop working — a load-order change, a malli upgrade, a hook that stops firing —
  and every green in this suite would then be a green over unchecked specs, which is
  byte-identical to a green over checked ones.

  IT ASSERTS THROUGH A REAL PRODUCTION VAR, not a fixture schema declared here. A
  canary that registers its own `m/=>` and instruments it proves that malli works;
  it says nothing about whether THIS suite's seam armed THIS tree. That is the
  aimed-one-layer-off failure `.claude/rules/review-discipline.md` describes, and
  its green is indistinguishable from the real thing.

  REVERT-TO-BREAK: remove the `:kaocha.hooks/post-load` entry from
  `tools/renderer-gen/tests.edn`. `instrumentation-is-live` must FAIL.
  CONTROL: every other test in this suite must stay GREEN on that same mutant —
  they pass uninstrumented, which is what makes the failure attributable to the
  seam rather than to a broken tree."
  (:require
   [clojure.test :refer [deftest is testing]]
   [lvgl-codegen.emit-proto :as emit-proto]
   [lvgl-codegen.instrument :as inst]
   [lvgl-codegen.palette-ladder :as pl]
   [malli.core :as m]))

(deftest instrumentation-is-live
  (testing "a call violating a real production spec is REFUSED by malli"
    ;; `hex->rgb8`'s spec is [:=> [:cat [:re hex-pattern]] [:vector :int]]. "nope"
    ;; matches no canonical hex, so an armed run must refuse it with malli's own
    ;; error — NOT with the function's `ex-info`, which is why the message is
    ;; matched rather than the class. Both are ExceptionInfo, so a bare `thrown?`
    ;; here would pass in the UNARMED state too and this canary would be incapable
    ;; of failing.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid-input"
                          (pl/hex->rgb8 "nope"))
        (str "instrumentation is NOT armed: the call was refused by the function's "
             "own guard rather than by malli, or not refused at all. Every other "
             "green in this suite is then a green over unchecked specs."))))

(deftest the-escape-hatch-reaches-the-function-body
  (testing "`uninstrumented` bypasses the wrapper so a guard can still be tested"
    ;; The escape is what makes a negative-path test possible under arming. If it
    ;; stopped working, the guard tests would silently become tests of malli's
    ;; wrapper — passing, and proving nothing about the function.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a canonical #RRGGBB hex"
                          ((inst/uninstrumented #'pl/hex->rgb8) "nope"))
        "the escape must reach the body's own guard, not malli's wrapper"))
  (testing "and it is transparent on valid input"
    (is (= [18 18 31] ((inst/uninstrumented #'pl/hex->rgb8) "#12121F")))))

(deftest the-vacuity-floor-is-a-real-number
  (testing "the floor rules out an empty population rather than tracking it"
    (is (pos? inst/spec-floor))
    (is (>= (reduce + 0 (map (comp count val) (m/function-schemas)))
            inst/spec-floor)
        "fewer registered schemas than the floor means arming judged almost nothing")))

(deftest unknown-widget-tag-reaches-its-own-refusal
  (testing "an unrecognised tag gets emit-proto's diagnostic, not a malli input error"
    ;; REGRESSION. `widget-type-of` exists to refuse an unknown tag by NAME, and
    ;; its caller `emit-widget` accepts `[:map [:tag keyword?]]` — so an unknown
    ;; tag is a value this code is written to meet. Its spec once declared the
    ;; input as the closed tag enum, which under instrumentation refused the tag
    ;; at the boundary and made that arm unreachable: the caller saw
    ;; `:malli.core/invalid-input` and never the message listing the legal tags.
    ;;
    ;; This asserts through the REAL var with instrumentation LIVE (the seam this
    ;; namespace's first test proves is armed), so it fails if the input schema
    ;; is ever narrowed back to the enum.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown widget type: :lv_definitely_not_a_widget"
         (emit-proto/emit-widget {:tag :lv_definitely_not_a_widget})))
    (testing "and the diagnostic still names the legal tags"
      (is (re-find #"Valid LVGL tags: "
                   (try (emit-proto/emit-widget {:tag :lv_definitely_not_a_widget})
                        (catch clojure.lang.ExceptionInfo e (ex-message e))))))))
