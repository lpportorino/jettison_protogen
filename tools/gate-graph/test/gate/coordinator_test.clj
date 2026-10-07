(ns gate.coordinator-test
  "Barrier-based concurrency, causal ordering, honest coverage and real command dispatch proofs."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.coordinator :as coordinator]
            [gate.run-contract :as r]
            [gate.run-test :as fixture]
            [malli.core :as m])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def options {:jobs 2 :claims {}})
(def passed {:outcome :passed :coverage fixture/coverage :reason nil})
(def failed {:outcome :failed :coverage fixture/coverage :reason :command-failed})
(def cancelled {:outcome :cancelled :coverage nil :reason :cancellation-requested})
(defn declaration [id deps] (fixture/declaration id deps))
(defn results [batch] (into {} (map (juxt :gate identity) (:dispatches batch))))
(defn awaited [^CountDownLatch latch] (.await latch 10 TimeUnit/SECONDS))

(deftest hard-prerequisites-block-but-order-only-work-and-independent-work-continue
  (let [calls (atom [])
        gates [(declaration "a" [])
               (declaration "b" [{:gate "a" :relation :requires}])
               (declaration "c" [{:gate "b" :relation :produces}])
               (declaration "d" [{:gate "a" :relation :after}])
               (declaration "e" [{:gate "unused" :relation :invalidates}])
               (declaration "unused" [])]
        batch (coordinator/run! gates ["c" "d" "e"] options
                                (fn [gate _] (swap! calls conj (:id gate))
                                  (if (= "a" (:id gate)) failed passed)) (atom false))
        by-id (results batch)]
    (is (m/validate r/Batch batch))
    (is (= :failed (:status batch)))
    (is (= #{"a" "d" "e"} (set @calls)))
    (is (= ["a"] (:blocked-by (by-id "b"))))
    (is (= ["b"] (:blocked-by (by-id "c"))))
    (is (= :deselected (:outcome (by-id "unused"))))
    (is (nil? (:adapter-interval (by-id "b"))))
    (let [start (get-in by-id ["d" :adapter-interval :start-ns])]
      (is (string? start))
      (when (string? start)
        (is (<= (Long/parseLong (:at-ns (by-id "a"))) (Long/parseLong start)))))))

(deftest worker-limit-is-enforced-before-submission
  (let [entered (CountDownLatch. 2) release (CountDownLatch. 1)
        running (atom 0) maximum (atom 0) calls (atom [])
        gates (mapv #(declaration % []) ["a" "b" "c" "d"])
        work (future
               (coordinator/run! gates (mapv :id gates) options
                                 (fn [gate _]
                                   (swap! maximum max (swap! running inc))
                                   (swap! calls conj (:id gate))
                                   (.countDown entered)
                                   (try (when-not (awaited release) (throw (ex-info "Test barrier timed out" {})))
                                        passed (finally (swap! running dec)))) (atom false)))]
    (try
      (is (awaited entered))
      (is (= 2 @running))
      (is (= #{"a" "b"} (set @calls)))
      (finally (.countDown release)))
    (let [batch (deref work 15000 :timeout)]
      (is (map? batch))
      (is (= :passed (:status batch)))
      (is (= 2 @maximum))
      (is (= 0 @running))
      (is (= ["a" "b" "c" "d"] (mapv :gate (:dispatches batch)))))))

(deftest exclusive-claims-do-not-serialize-independent-resources
  (let [entered (CountDownLatch. 2) release (CountDownLatch. 1)
        calls (atom []) resources (atom #{}) collision (atom false)
        claims {"a" ["compiler"] "b" ["compiler"] "c" ["database"]}
        gates (mapv #(declaration % []) ["a" "b" "c"])
        work (future
               (coordinator/run! gates (mapv :id gates) {:jobs 3 :claims claims}
                                 (fn [gate _]
                                   (let [resource (first (get claims (:id gate)))]
                                     (swap! resources (fn [held] (when (held resource) (reset! collision true)) (conj held resource)))
                                     (swap! calls conj (:id gate))
                                     (.countDown entered)
                                     (try (when-not (awaited release) (throw (ex-info "Test barrier timed out" {})))
                                          passed (finally (swap! resources disj resource))))) (atom false)))]
    (try
      (is (awaited entered))
      (is (= #{"a" "c"} (set @calls)))
      (finally (.countDown release)))
    (is (= :passed (:status (deref work 15000 :timeout))))
    (is (false? @collision))
    (is (= #{} @resources))))

(deftest assertions-exceptions-and-invalid-coverage-cannot-be-success
  (doseq [[backend reason]
          [[(fn [_ _] (throw (AssertionError. "secret assertion"))) :adapter-exception]
           [(fn [_ _] (throw (ex-info "secret value" {:secret "do not serialize"}))) :adapter-exception]
           [(fn [_ _] {:outcome :passed}) :invalid-adapter-result]
           [(fn [_ _] (assoc passed :coverage nil)) :coverage-mismatch]
           [(fn [_ _] (assoc-in passed [:coverage :count] 0)) :coverage-mismatch]
           [(fn [_ _] (assoc-in passed [:coverage :observed] fixture/digest-a)) :coverage-mismatch]
           [(fn [_ _] (assoc passed :reason :cache-hit)) :invalid-adapter-result]]]
    (let [batch (coordinator/run! [(declaration "a" [])] ["a"] options backend (atom false))]
      (is (= :failed (:status batch)))
      (is (= reason (get-in batch [:dispatches 0 :work :reason])))
      (is (not (.contains (pr-str batch) "secret"))))))

(deftest cached-coverage-is-historical-and-still-satisfies-a-prerequisite
  (let [batch (coordinator/run!
               [(declaration "a" []) (declaration "b" [{:gate "a" :relation :requires}])]
               ["b"] options (fn [gate _] (if (= "a" (:id gate))
                                            (assoc passed :outcome :cached :reason :cache-hit) passed)) (atom false))]
    (is (= :passed (:status batch)))
    (is (= [:cached :passed] (mapv :outcome (:dispatches batch))))))

(deftest cancellation-stops-pending-work-and-waits-for-cooperative-cleanup
  (let [started (CountDownLatch. 1) token (atom false) cleaned (atom false) calls (atom [])
        work (future
               (coordinator/run! [(declaration "a" []) (declaration "b" [])] ["a" "b"]
                                 (assoc options :jobs 1)
                                 (fn [gate cancellation]
                                   (swap! calls conj (:id gate)) (.countDown started)
                                   (let [deadline (+ (System/nanoTime) 10000000000)]
                                     (loop [] (when (and (not @cancellation) (< (System/nanoTime) deadline))
                                                (Thread/sleep 5) (recur))))
                                   (reset! cleaned true) cancelled) token))]
    (try (is (awaited started)) (finally (reset! token true)))
    (let [batch (deref work 15000 :timeout)]
      (is (= :cancelled (:status batch)))
      (is @cleaned)
      (is (= ["a"] @calls))
      (is (= [:cancelled :cancelled] (mapv :outcome (:dispatches batch))))
      (is (nil? (get-in batch [:dispatches 1 :adapter-interval]))))))

(deftest invalid-plan-and-claims-are-rejected-before-backend-invocation
  (let [calls (atom 0) backend (fn [_ _] (swap! calls inc) passed)]
    (doseq [claims [{"missing" ["lock"]} {"a" ["lock" "lock"]}]]
      (is (= :invalid-resource-claims
             (:code (fixture/error #(coordinator/run! [(declaration "a" [])] ["a"]
                                                      {:jobs 1 :claims claims} backend (atom false)))))))
    (is (= :missing-dependency
           (:code (fixture/error #(coordinator/run! [(declaration "a" [{:gate "missing" :relation :requires}])]
                                                    ["a"] options backend (atom false))))))
    (is (zero? @calls))))

(deftest real-process-exit-is-preserved-with-independent-synthetic-work-witness
  (let [gates [(assoc (declaration "a" []) :command ["/bin/sh" "-c" "exit 7"])
               (assoc (declaration "b" []) :command ["/bin/sh" "-c" "printf witnessed"])]
        backend (fn [gate _]
                  (let [process (.start (doto (ProcessBuilder. ^java.util.List (:command gate))
                                          (.redirectError ProcessBuilder$Redirect/DISCARD)))
                        output (slurp (.getInputStream process))
                        code (.waitFor process)]
                    (if (zero? code)
                      (assoc passed :coverage (when (= "witnessed" output) fixture/coverage)) failed)))
        batch (coordinator/run! gates ["a" "b"] options backend (atom false))]
    (is (= :failed (:status batch)))
    (is (= [:failed :passed] (mapv :outcome (:dispatches batch))))))

(deftest pre-cancelled-work-never-invokes-an-adapter-and-success-does-not-request-cancellation
  (let [calls (atom 0) backend (fn [_ _] (swap! calls inc) passed)
        gates [(declaration "a" [])]
        batch (coordinator/run! gates ["a"] options backend (atom true))
        token (atom false)]
    (is (= :cancelled (:status batch)))
    (is (zero? @calls))
    (is (nil? (get-in batch [:dispatches 0 :adapter-interval])))
    (is (= :passed (:status (coordinator/run! gates ["a"] options backend token))))
    (is (false? @token))))

(deftest caller-interruption-requests-cancellation-and-restores-interrupt-after-cleanup
  (let [started (CountDownLatch. 1) token (atom false) cleaned (atom false) answer (promise)
        caller (Thread.
                (fn []
                  (let [batch (coordinator/run!
                               [(declaration "a" [])] ["a"] options
                               (fn [_ cancellation]
                                 (.countDown started)
                                 (let [deadline (+ (System/nanoTime) 10000000000)]
                                   (loop [] (when (and (not @cancellation) (< (System/nanoTime) deadline))
                                              (Thread/sleep 5) (recur))))
                                 (reset! cleaned true) cancelled) token)]
                    (deliver answer {:batch batch :interrupted? (.isInterrupted (Thread/currentThread))}))))]
    (.start caller)
    (try (is (awaited started)) (finally (.interrupt caller)))
    (let [result (deref answer 15000 :timeout)]
      (is (= :cancelled (get-in result [:batch :status])))
      (is (true? (:interrupted? result)))
      (is @cleaned))
    (.join caller 1000)
    (is (not (.isAlive caller)))))

(deftest generated-failure-propagation-matches-independent-reachability-oracle
  (let [property
        (prop/for-all [edges (gen/vector (gen/tuple (gen/choose 0 7) (gen/choose 0 7)) 0 25)
                       failed-nodes (gen/set (gen/choose 0 7))]
                      (let [edges (set (filter (fn [[from to]] (< from to)) edges))
                            names (mapv #(str "gate-" %) (range 8))
                            gates (mapv (fn [n] (declaration (names n)
                                                             (mapv (fn [[from _]] {:gate (names from) :relation :requires})
                                                                   (filter #(= n (second %)) edges)))) (range 8))
                            predecessors (fn [n]
                                           (loop [reachable #{n}]
                                             (let [expanded (into reachable (for [[from to] edges :when (reachable to)] from))]
                                               (if (= reachable expanded) (disj reachable n) (recur expanded)))))
                            expected (mapv (fn [n] (cond (some failed-nodes (predecessors n)) :blocked
                                                         (failed-nodes n) :failed :else :passed)) (range 8))
                            batch (coordinator/run! gates names options
                                                    (fn [gate _] (if (contains? (set (map names failed-nodes)) (:id gate)) failed passed))
                                                    (atom false))
                            observed (results batch)]
                        (= expected (mapv #(:outcome (observed %)) names))))
        result (tc/quick-check 100 property :seed 20261008)]
    (is (:pass? result) (pr-str result))))
