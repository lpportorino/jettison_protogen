(ns protodoc.gencorpus.edge-test
  "Edge-case pins closing the completeness-critic gaps: the string :in → [:enum]
   half of bug #7 (no live proto-db field carries :in, so it is pinned
   synthetically), the int64 type-bound (bug #1 analog of the int32/uint32 pin),
   the uint64 empty-range fail-loud, and the NaN-into-a-constrained-float
   violating path (bug #4's violating half) confirmed REJECTED by the oracle."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
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
