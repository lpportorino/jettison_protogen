(ns gate.attempt-test
  "Live bytes to cached decisions to receipt publication, with forced runs and instability controls."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.attempt :as attempt]
            [gate.canonical :as canonical]
            [gate.inputs :as inputs]
            [gate.inputs-test :as files]
            [gate.run-contract :as r]
            [gate.run-test :as fixture]
            [gate.store :as store]
            [gate.trace-test :refer [with-directory]]
            [malli.core :as m])
  (:import [java.nio.file Path Files]))

(def passed {:outcome :passed :coverage fixture/coverage :reason nil})
(defn request [root]
  {:directory (str root) :cache-directory (str (.resolve ^Path root "cache"))
   :run "fixture-run" :attempt "fixture-attempt" :gate fixture/gate
   :evidence files/evidence :limits inputs/default-limits :dependencies []
   :force? false :isolation-verified? true})
(defn backend [root calls]
  (fn [_ _]
    (swap! calls inc)
    (files/put! root "out/result.edn" (slurp (str (.resolve ^Path root "src/a.clj"))))
    passed))
(defn invoke [req f] (attempt/run! req f (constantly []) (atom false)))

(deftest cold-pass-then-real-hit-unrelated-hit-changed-miss-and-forced-run
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [calls (atom 0) execute (backend root calls) req (request root)
            cold (invoke req execute) hit (invoke (assoc req :run "second") execute)]
        (is (m/validate r/AttemptObservation cold))
        (is (= :passed (get-in cold [:work :outcome])))
        (is (= :stored (:publication cold)))
        (is (= :cached (get-in hit [:work :outcome])))
        (is (= :cache-hit (get-in hit [:decision :reason])))
        (is (nil? (:after hit)))
        (is (= 1 @calls))
        (files/put! root "unrelated/readme.txt" "unrelated")
        (is (= :cached (get-in (invoke req execute) [:work :outcome])))
        (is (= 1 @calls))
        (files/put! root "src/a.clj" "b")
        (let [changed (invoke req execute)]
          (is (= :passed (get-in changed [:work :outcome])))
          (is (not= (get-in cold [:decision :key]) (get-in changed [:decision :key])))
          (is (= :stored (:publication changed)))
          (is (= 2 @calls)))
        (let [forced (invoke (assoc req :force? true) execute)]
          (is (= :forced (get-in forced [:decision :reason])))
          (is (= :passed (get-in forced [:work :outcome])))
          (is (= :existing (:publication forced)))
          (is (= 3 @calls)))))))

(deftest mutable-workspace-can-be-observed-but-cannot-earn-or-use-receipts
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [calls (atom 0) execute (backend root calls) req (request root)]
        (invoke req execute)
        (let [observation (invoke (assoc req :isolation-verified? false) execute)]
          (is (= :passed (get-in observation [:work :outcome])))
          (is (= :unproven-isolation (get-in observation [:decision :reason])))
          (is (= :unproven-isolation (get-in observation [:admission :reason])))
          (is (= :not-attempted (:publication observation)))
          (is (= :not-attempted (:lookup observation)))
          (is (= 2 @calls)))))))

(deftest input-changes-during-success-refuse-publication-with-live-before-after-evidence
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [observation (invoke (request root)
                                (fn [_ _] (files/put! root "src/a.clj" "b")
                                  (files/put! root "out/result.edn" "a") passed))]
        (is (= :passed (get-in observation [:work :outcome])))
        (is (= :input-unstable (get-in observation [:admission :reason])))
        (is (= :not-attempted (:publication observation)))
        (is (not= (:before observation) (:after observation)))
        (is (= :absent (:reason (store/lookup (:cache-directory (request root)) (get-in observation [:decision :key])))))))))

(deftest missing-output-forces-rerun-and-regeneration-can-reuse-the-original-receipt
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [calls (atom 0) execute (backend root calls) req (request root)]
        (invoke req execute)
        (Files/delete (.resolve ^Path root "out/result.edn"))
        (let [restored (invoke req execute)]
          (is (= :outputs-changed (get-in restored [:decision :reason])))
          (is (= :existing (:publication restored)))
          (is (= 2 @calls)))))))

(deftest failing-assertions-and-vacuous-work-do-not-create-cache-evidence
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (doseq [execute [(fn [_ _] (throw (AssertionError. "fixture failure")))
                       (fn [_ _] {:outcome :passed :coverage nil :reason nil})
                       (fn [_ _] (assoc passed :outcome :cached :reason :cache-hit))]]
        (let [observation (invoke (request root) execute)]
          (is (= :error (get-in observation [:work :outcome])))
          (is (= :failed-execution (get-in observation [:admission :reason])))
          (is (= :not-attempted (:publication observation))))))))

(deftest conflicting-successful-outputs-quarantine-a-key-without-rewriting-execution-outcome
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [req (request root) calls (atom 0) execute (backend root calls)
            original (invoke req execute)
            conflicting (invoke (assoc req :force? true)
                                (fn [_ _] (files/put! root "out/result.edn" "different") passed))]
        (is (= :passed (get-in conflicting [:work :outcome])))
        (is (= :conflict (:publication conflicting)))
        (is (= :conflict (:reason (store/lookup (:cache-directory req) (get-in original [:decision :key])))))
        (let [again (invoke req execute)]
          (is (= :conflict (:lookup again)))
          (is (= :passed (get-in again [:work :outcome])))
          (is (= :conflict (:publication again))))))))

(deftest dependency-result-change-during-work-prevents-publication
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [dependencies [{:gate "producer" :relation :produces :key fixture/digest-a :result fixture/digest-a}]
            req (-> (request root)
                    (assoc :dependencies dependencies)
                    (assoc-in [:gate :dependencies] [{:gate "producer" :relation :produces}]))
            observation (attempt/run! req (backend root (atom 0))
                                      (constantly (assoc-in dependencies [0 :result] fixture/digest-b)) (atom false))]
        (is (= :input-unstable (get-in observation [:admission :reason])))
        (is (= :not-attempted (:publication observation)))))))

(deftest attempt-evidence-has-a-canonical-representation
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [observation (invoke (request root) (backend root (atom 0)))]
        (is (string? (canonical/encode observation 1048576)))
        (is (= observation (admission/decode (canonical/encode observation 1048576)
                                             :attempt-observation admission/default-limits)))
        (is (not (.contains (canonical/encode observation 1048576) (str root))))))))

(deftest cancellation-over-a-valid-cache-hit-does-not-claim-reused-work
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [calls (atom 0) execute (backend root calls) req (request root)]
        (invoke req execute)
        (let [cancelled (attempt/run! req execute (constantly []) (atom true))]
          (is (= 1 @calls))
          (is (= :cancelled (get-in cancelled [:work :outcome])))
          (is (= :cancellation-requested (get-in cancelled [:decision :reason])))
          (is (= :run (get-in cancelled [:decision :action])))
          (is (= :not-attempted (:publication cancelled))))))))

(deftest failed-post-run-dependency-observation-preserves-success-but-refuses-reuse
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [dependencies [{:gate "producer" :relation :produces :key fixture/digest-a :result fixture/digest-a}]
            req (-> (request root) (assoc :dependencies dependencies)
                    (assoc-in [:gate :dependencies] [{:gate "producer" :relation :produces}]))]
        (doseq [observer [(constantly []) (constantly {}) (fn [] (throw (ex-info "private evidence failed" {})))]]
          (let [observation (attempt/run! req (backend root (atom 0)) observer (atom false))]
            (is (= :passed (get-in observation [:work :outcome])))
            (is (= :dependency-unknown (get-in observation [:admission :reason])))
            (is (nil? (:dependencies-after observation)))
            (is (= :not-attempted (:publication observation)))))))))
