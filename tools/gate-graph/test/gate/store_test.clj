(ns gate.store-test
  "Real filesystem publication races and malformed-receipt refusal."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.cache :as cache]
            [gate.canonical :as canonical]
            [gate.run-test :as fixture]
            [gate.store :as store]
            [gate.trace-test :refer [with-directory]])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(deftest declared-gates-and-receipts-roundtrip-through-bounded-admission
  (doseq [[value target] [[[fixture/gate] :gate-definitions]
                          [fixture/snapshot :input-snapshot] [(fixture/earn) :cache-receipt]]]
    (is (= value (admission/decode (canonical/encode value 1048576) target admission/default-limits)))))

(deftest publication-is-pass-only-and-readback-is-bounded
  (with-directory
    (fn [root]
      (let [directory (str (.resolve ^Path root "cache")) receipt (fixture/earn)]
        (is (= {:status :miss :reason :absent} (store/lookup directory (:key receipt))))
        (is (= {:status :stored :receipt receipt} (store/publish! directory receipt)))
        (is (= {:status :hit :receipt receipt} (store/lookup directory (:key receipt))))
        (is (= {:status :existing :receipt receipt} (store/publish! directory (assoc receipt :run "second-run"))))))))

(deftest concurrent-runs-cannot-overwrite-the-first-earned-receipt
  (with-directory
    (fn [root]
      (let [directory (str root) receipt (fixture/earn) barrier (promise)
            writers (mapv (fn [i] (future @barrier (store/publish! directory (assoc receipt :run (str "run-" i))))) (range 12))]
        (deliver barrier true)
        (let [results (mapv deref writers) retained (:receipt (store/lookup directory (:key receipt)))]
          (is (= 1 (count (filter #(= :stored (:status %)) results))))
          (is (= 11 (count (filter #(= :existing (:status %)) results))))
          (is (every? #(= retained (:receipt %)) results))
          (with-open [entries (Files/list root)]
            (is (= 1 (.count entries)))))))))

(deftest same-input-key-with-different-successful-results-is-a-visible-conflict
  (with-directory
    (fn [root]
      (let [receipt (fixture/earn) directory (str root)
            altered-outputs (assoc-in fixture/outputs [0 :digest] fixture/digest-b)
            conflicting (assoc receipt :outputs altered-outputs
                               :result (cache/result-identity fixture/coverage altered-outputs))]
        (store/publish! directory receipt)
        (is (= :cache-conflict (:code (fixture/error #(store/publish! directory conflicting)))))
        (is (= {:status :miss :reason :conflict} (store/lookup directory (:key receipt))))
        (is (= :cache-conflict (:code (fixture/error #(store/publish! directory receipt)))))))))

(deftest corrupt-symlinked-and-oversize-receipts-never-hit
  (with-directory
    (fn [root]
      (let [directory (str root) receipt (fixture/earn)
            path (.resolve ^Path root (str (:key receipt) ".edn"))]
        (doseq [text ["#=(System/exit 0)" "{}" (str (canonical/encode receipt 1048576) " nil")
                      (canonical/encode (assoc receipt :key fixture/digest-b) 1048576)
                      (canonical/encode (assoc receipt :result fixture/digest-b) 1048576)
                      (apply str (repeat 4194305 "x"))]]
          (spit (str path) text)
          (is (= {:status :miss :reason :invalid} (store/lookup directory (:key receipt)))))
        (Files/delete path)
        (let [target (.resolve ^Path root "target")]
          (spit (str target) (canonical/encode receipt 1048576))
          (Files/createSymbolicLink path target (make-array FileAttribute 0))
          (is (= {:status :miss :reason :invalid} (store/lookup directory (:key receipt)))))
        (is (= :cache-invalid-receipt (:code (fixture/error #(store/publish! directory receipt)))))))))
