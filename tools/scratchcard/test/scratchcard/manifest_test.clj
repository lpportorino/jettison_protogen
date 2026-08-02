(ns scratchcard.manifest-test
  "Pins the manifest contract: closed, validated both ways, and STABLE.

  The stability tests are the ones that would otherwise rot silently. A
  manifest that validates perfectly but re-serialises in a different key order
  on every run turns every diff into noise, and the reader stops using the
  archive — which is a total loss of the feature, reported by nothing."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scratchcard.manifest :as mf])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  ^File []
  (.toFile (Files/createTempDirectory "scratchcard-manifest-test"
                                      (into-array FileAttribute []))))

(def ^:private sha64 (str/join (repeat 64 "a")))

(def ^:private good
  {:run {:id "0001" :utc "20260802T132244Z" :card "demo"
         :status :ok :elapsed-ms 812}
   :protogen {:sha "abc" :dirty? false}
   :wasm {:sha256 "def" :abi 4}
   :toolchain {:image-id "sha256:x"}
   :protocol {:ticks 3 :tick-ms 16 :dpi 160}
   :host {:load-average 6.2}
   :matrix {:families [0 1 2] :modes [0 1]}
   :input {:sha256 "ghi"}
   :lanes {:producers [:tree-by-expect :overlap :deadzone :opa]
           :thresholds {:overlap/gap-px 0 :deadzone/gap-px 0}
           :caps {:vis-px? true}
           :classes-sha "jkl"
           :declined [:layers :palette :border]}
   :renders [{:family 0 :dark 1 :w 800 :h 480 :bp 0
              :disp-tier "DISP_LARGE" :status :ok :fb-sha256 sha64}]
   :findings {:count 0 :by-invariant {}}})

(defn- refusal
  "The :error code from validating `m`, or :NOT-REFUSED."
  [m]
  (try (mf/validate! m) :NOT-REFUSED
       (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))

(deftest a-well-formed-manifest-validates
  (is (= good (mf/validate! good)))
  (is (nil? (mf/explain good))))

(deftest unknown-keys-are-refused-not-ignored
  (testing "at the top level, and the message names the accepted set"
    (is (= "INVALID_MANIFEST" (refusal (assoc good :sneaky 1))))
    (let [data (ex-data (try (mf/validate! (assoc good :sneaky 1))
                             (catch clojure.lang.ExceptionInfo e e)))]
      (is (contains? (set (:accepted data)) :run))
      (testing "the accepted list is DERIVED from the schema, so it cannot drift"
        (is (= (set (:accepted data))
               (set (keys good)))))))
  (testing "inside the closed :run block"
    (is (= "INVALID_MANIFEST" (refusal (assoc-in good [:run :extra] 1)))))
  (testing "inside a closed render entry"
    (is (= "INVALID_MANIFEST"
           (refusal (update good :renders (fn [rs] [(assoc (first rs) :extra 1)]))))))
  (testing "inside the closed :lanes block"
    (is (= "INVALID_MANIFEST" (refusal (assoc-in good [:lanes :extra] 1))))))

(deftest required-keys-and-value-shapes-are-enforced
  (testing "a missing top-level block"
    (is (= "INVALID_MANIFEST" (refusal (dissoc good :lanes)))))
  (testing "a missing :lanes field — the block that makes a diff attributable"
    (is (= "INVALID_MANIFEST" (refusal (update good :lanes dissoc :classes-sha)))))
  (testing "an out-of-vocabulary status"
    (is (= "INVALID_MANIFEST" (refusal (assoc-in good [:run :status] :maybe)))))
  (testing "a theme family outside 0..2 — the wasm rejects these, so we do too"
    (is (= "INVALID_MANIFEST"
           (refusal (update good :renders (fn [rs] [(assoc (first rs) :family 3)]))))))
  (testing "a breakpoint outside 0..3"
    (is (= "INVALID_MANIFEST"
           (refusal (update good :renders (fn [rs] [(assoc (first rs) :bp 4)]))))))
  (testing "a framebuffer hash that is not a sha256"
    (is (= "INVALID_MANIFEST"
           (refusal (update good :renders (fn [rs] [(assoc (first rs) :fb-sha256 "nope")]))))))
  (testing "a blank run id"
    (is (= "INVALID_MANIFEST" (refusal (assoc-in good [:run :id] ""))))))

(deftest round-trip-is-lossless
  (let [dir (temp-dir)]
    (mf/write! dir good)
    (is (= good (mf/read-manifest dir)))))

(deftest serialisation-is-stable-so-diffs-stay-readable
  (let [a (temp-dir)
        b (temp-dir)]
    (mf/write! a good)
    ;; Same DATA, different insertion order. An unsorted writer emits
    ;; different bytes here and every run diffs against every other run.
    (mf/write! b (into {} (reverse (seq good))))
    (is (= (slurp (io/file a mf/manifest-name))
           (slurp (io/file b mf/manifest-name))))
    (testing "and a one-field change produces a SMALL diff, not a reshuffle"
      (let [c (temp-dir)]
        (mf/write! c (assoc-in good [:run :elapsed-ms] 999))
        (let [orig (str/split-lines (slurp (io/file a mf/manifest-name)))
              changed (str/split-lines (slurp (io/file c mf/manifest-name)))]
          (is (= (count orig) (count changed)))
          (is (= 1 (count (remove true? (map = orig changed))))))))))

(deftest an-invalid-manifest-never-reaches-disk
  ;; Writing first and validating later would leave a broken file for the next
  ;; reader to trip over.
  (let [dir (temp-dir)]
    (is (thrown? clojure.lang.ExceptionInfo (mf/write! dir (dissoc good :run))))
    (is (not (.isFile (io/file dir mf/manifest-name))))))

(deftest reading-refuses-a-missing-or-drifted-file
  (testing "absent"
    (let [dir (temp-dir)
          e (is (thrown? clojure.lang.ExceptionInfo (mf/read-manifest dir)))]
      (is (= "NO_MANIFEST" (:error (ex-data e))))))
  (testing "present but out of shape — caught on READ, not three steps later"
    (let [dir (temp-dir)]
      (spit (io/file dir mf/manifest-name) (pr-str (dissoc good :lanes)))
      (let [e (is (thrown? clojure.lang.ExceptionInfo (mf/read-manifest dir)))]
        (is (= "INVALID_MANIFEST" (:error (ex-data e))))))))

(deftest a-failed-run-is-representable
  ;; EVERY invocation writes a manifest, including one that failed before it
  ;; rendered anything — that run is precisely the one a reader wants to diff.
  (let [failed (-> good
                   (assoc :run {:id "0002" :utc "20260802T140000Z" :card "demo"
                                :status :error :elapsed-ms 12
                                :error {:code "INPUT_EVAL_FAILED"
                                        :message "unbound tag :lv_nope"}})
                   (assoc :renders []))]
    (is (= failed (mf/validate! failed)))
    (testing "and it round-trips"
      (let [dir (temp-dir)]
        (mf/write! dir failed)
        (is (= failed (mf/read-manifest dir)))))))
