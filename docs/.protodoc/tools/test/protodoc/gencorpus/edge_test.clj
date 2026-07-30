(ns protodoc.gencorpus.edge-test
  "Edge-case pins closing the completeness-critic gaps: the string :in → [:enum]
   half of bug #7 (no live proto-db field carries :in, so it is pinned
   synthetically), the int64 type-bound (bug #1 analog of the int32/uint32 pin),
   the uint64 empty-range fail-loud, and the NaN-into-a-constrained-float
   violating path (bug #4's violating half) confirmed REJECTED by the oracle."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [protodoc.gencorpus :as gencorpus]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool]
            [protodoc.manifest :as manifest])
  (:import [com.google.protobuf Descriptors$Descriptor]))

(set! *warn-on-reflection* true)

(def ^:private binpb "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private pool* (delay (pool/load-pool binpb)))

(deftest string-in-becomes-enum
  (testing "bug #7 (:in half): a string :in set → [:enum allowed…] (synthetic — no live proto-db field uses :in)"
    (let [s (manifest/constraints->malli
             {:type :string :constraints {:in ["recording_day" "recording_heat"]}} {})]
      (is (= [:enum "recording_day" "recording_heat"] s))
      (is (m/validate s "recording_day"))
      (is (not (m/validate s "something_else"))))))

(deftest int64-type-bounded
  (testing "bug #1 (int64): int64 maps to the full signed-64 range, honoring constraints"
    (is (= [:int {:min -9223372036854775808 :max 9223372036854775807}]
           (manifest/constraints->malli {:type :int64} {})))
    (let [s (manifest/constraints->malli {:type :int64 :constraints {:gte 0 :lte 1000}} {})]
      (is (= [:int {:min 0 :max 1000}] s))
      (is (not (m/validate s -1)))
      (is (m/validate s 1000)))))

(deftest uint64-empty-range-fails-loud
  (testing "bug #25 analog (uint64): an unsatisfiable uint64 range throws, never a silent accept"
    (is (thrown? clojure.lang.ExceptionInfo
                 (manifest/constraints->malli {:type :uint64 :constraints {:gte 100 :lte 50}} {})))))

(deftest nan-into-constrained-float-is-rejected
  (testing "bug #4 (violating half): NaN in a constrained float field is REJECTED by the oracle"
    ;; ser.ObjectDetection x1/x2/y1/y2 carry a live [-1, 1] float bound.
    (let [d ^Descriptors$Descriptor (get @pool* "ser.ObjectDetection")]
      (is (some? d) "ser.ObjectDetection is in the pool")
      (let [valid (pool/build-msg d {"x1" 0.5 "y1" 0.5 "x2" 0.5 "y2" 0.5})
            nan   (pool/build-msg d {"x1" (double Double/NaN) "y1" 0.5 "x2" 0.5 "y2" 0.5})
            inf   (pool/build-msg d {"x1" (double Double/POSITIVE_INFINITY) "y1" 0.5 "x2" 0.5 "y2" 0.5})]
        (is (oracle/valid? valid) "a finite in-range ObjectDetection is valid")
        (is (not (oracle/valid? nan)) "NaN violates the [-1,1] float bound")
        (is (not (oracle/valid? inf)) "+Inf violates the [-1,1] float bound")))))

;; ═══════════════════════════════════════════════════════════════════════════
;; A TWO-SIDED bound owes a violation on BOTH sides
;;
;; `bad-numeric` returns ONE value through a `cond`, so handing it the whole
;; constraint map for a two-sided bound yields only the first branch that matches.
;; `violating-entries` did exactly that, so for `{:lt 360 :gt -360}` — the shape
;; cmd.RotaryPlatform.SetPlatformAzimuth.value carries — the `:lt` arm fired and the
;; LOWER bound was never exercised. Negative coverage for the whole gt/lt-together
;; class was half-blind, and silently: the corpus still had an entry for the field,
;; just never one testing the floor.
;;
;; The committed goldens do NOT sample such a field, which is exactly why nothing
;; caught it — this pins the arithmetic directly rather than through a corpus that
;; happens not to reach it.
;;
;; WHAT THESE PINS DO NOT COVER, named because a mutation proved it rather than
;; because it was foreseen: they assert `bad-numerics`, i.e. the ARITHMETIC, not the
;; CALL SITE. Pointing `violating-entries`' numeric arms back at the singular
;; `bad-numeric` — the exact defect that shipped — leaves every assertion here GREEN.
;; So the wiring is guarded by nothing but review. Closing it needs a constrained
;; two-sided FieldDescriptor driven through `violating-entries` and its entry count
;; asserted; the probe is available (this namespace already loads the descriptor set
;; at `binpb`) and is not written. Disclosed rather than implied, per
;; `.claude/rules/renderer.md`'s convention for a gap that is named but not closed.

(def ^:private bad-numeric #'protodoc.gencorpus/bad-numeric)
(def ^:private bad-numerics #'protodoc.gencorpus/bad-numerics)

(deftest two-sided-bounds-violate-on-both-sides
  (testing "exclusive gt/lt together — one value per SIDE, not one per field"
    (is (= [-360.0 360.0] (vec (bad-numerics :double {:lt 360 :gt -360}))))
    (is (= [-360 360] (vec (bad-numerics :int32 {:lt 360 :gt -360})))))
  (testing "inclusive gte/lte together — one step past each boundary"
    (is (= [-1.0 101.0] (vec (bad-numerics :double {:lte 100 :gte 0}))))
    (is (= [0 101] (vec (bad-numerics :int32 {:lte 100 :gte 1})))))
  ;; REVERT-TO-BREAK: point `violating-entries`' numeric arms back at the singular
  ;; `bad-numeric`. This deftest must go red; `one-sided-bounds-are-unchanged` below
  ;; must stay GREEN, which is what attributes the red to the two-sided split rather
  ;; than to the boundary arithmetic itself.
  (testing "and the singular form is what was wrong — it drops a side"
    (is (= 360.0 (bad-numeric :double {:lt 360 :gt -360}))
        "the singular form returns ONE value, which is the defect this pins")))

(deftest one-sided-bounds-are-unchanged
  ;; THE CONTROL against over-generation: splitting per side must not invent a
  ;; violation for a bound that does not exist.
  (testing "upper-only and lower-only each yield exactly one"
    (is (= [360.0] (vec (bad-numerics :double {:lt 360}))))
    (is (= [-360.0] (vec (bad-numerics :double {:gt -360}))))
    (is (= [101.0] (vec (bad-numerics :double {:lte 100}))))
    (is (= [-1.0] (vec (bad-numerics :double {:gte 0})))))
  (testing "an unconstrained field yields none"
    (is (empty? (bad-numerics :double {})))
    (is (empty? (bad-numerics :int32 {})))))
