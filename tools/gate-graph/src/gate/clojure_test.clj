(ns gate.clojure-test
  "Observe trusted clojure.test suites through dynamic hooks without replacing global test functions."
  (:refer-clojure :exclude [run!])
  (:require [clojure.test :as test]
            [gate.canonical :as canonical]
            [gate.clock :as clock]
            [gate.contract :as c]
            [gate.run-contract :as r]
            [gate.test-graph :as test-graph]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def Namespaces [:vector {:min 1 :max 256} [:and :symbol [:fn #(<= 1 (count (str %)) 512)]]])
(def Options
  [:map {:closed true} [:run c/Id] [:gate c/Id] [:label c/Label] [:source-digest c/Digest]
   [:max-tests [:int {:min 1 :max 10000}]] [:max-assertions [:int {:min 1 :max 1000000}]]])
(def Failure [:map {:closed true} [:code [:enum :invalid-test-inventory :test-limit :test-observer-closed]]])
(def NativeVar [:fn #(instance? clojure.lang.Var %)])
(def Runner [:=> [:cat] :nil])
(def State [:fn #(instance? clojure.lang.Atom %)])
(def StateValue
  [:map {:closed true} [:executions [:vector {:max 10000} r/TestExecution]]
   [:attempts [:map-of {:max 10000} c/Id c/Attempt]]
   [:assertions [:int {:min 0 :max 1000000}]] [:unattributed r/TestCounts]
   [:problems [:set {:max 8} r/TestProblem]] [:closed? :boolean]])
(def ^:private zero-counts {:pass 0 :fail 0 :error 0})
(def ^:dynamic *invocation* nil)
(def ^:private ^:dynamic *test-var-delegate* nil)
(def ^:private ^:dynamic *report-delegate* nil)

(defn- identity-of
  "Hash the fully qualified test var name into a stable graph key; preserve the readable name as a label."
  [v]
  (let [label (str (ns-name (:ns (meta v))) "/" (:name (meta v)))]
    (when (> (count label) 512) (throw (ex-info "Test inventory refused" {:code :invalid-test-inventory})))
    {:key (str "test/" (canonical/sha256 label)) :label label}))
(m/=> identity-of [:=> [:cat NativeVar] r/TestIdentity])

(defn inventory
  "Discover the full expected test-var roster from already loaded namespaces before execution.
   Namespace hooks may execute it differently, but cannot silently omit declared tests from coverage.
   Empty/duplicate/missing namespaces and inventories beyond 10,000 tests refuse before any test runs."
  [namespaces]
  (when (or (not= (count namespaces) (count (set namespaces))) (some #(nil? (find-ns %)) namespaces))
    (throw (ex-info "Test inventory refused" {:code :invalid-test-inventory})))
  (let [result (->> namespaces (mapcat #(vals (ns-interns %)))
                    (filter #(fn? (:test (meta %)))) (take 10001) (map identity-of) (sort-by :key) vec)]
    (when (or (empty? result) (> (count result) 10000))
      (throw (ex-info "Test inventory refused" {:code :invalid-test-inventory})))
    result))
(m/=> inventory [:=> [:cat Namespaces] r/TestInventory])

(defn- instant
  "Return nanoseconds relative to the suite's monotonic origin; never use wall-clock adjustment."
  [origin]
  (clock/difference (System/nanoTime) origin))
(m/=> instant [:=> [:cat :int] c/Natural])

(defn- begin!
  "Allocate a bounded occurrence and attempt number under a lock shared by concurrent test callbacks."
  [state options v origin parent]
  (locking state
    (when (:closed? @state)
      (throw (ex-info "Test observation already closed" {:code :test-observer-closed})))
    (let [test-identity (identity-of v) index (count (:executions @state))
          attempt (inc (get-in @state [:attempts (:key test-identity)] 0))]
      (when (>= index (:max-tests options))
        (swap! state update :problems conj :test-limit)
        (throw (ex-info "Test observation limit" {:code :test-limit})))
      (swap! state (fn [s] (-> s (assoc-in [:attempts (:key test-identity)] attempt)
                               (update :executions conj
                                       (merge test-identity {:id (str "test-" index) :parent parent :attempt attempt
                                                             :interval {:start-ns (instant origin) :end-ns nil}
                                                             :counts zero-counts :returned? false})))))
      index)))
(m/=> begin! [:=> [:cat State Options NativeVar :int [:maybe c/Id]] [:int {:min 0 :max 9999}]])

(defn- assertion!
  "Attribute each report exactly once to the dynamically bound invocation, including joined futures.
   A late report refuses; callers must join asynchronous tests before returning from their test var."
  [state options index event]
  (locking state
    (when (or (:closed? @state)
              (and index (get-in @state [:executions index :interval :end-ns])))
      (throw (ex-info "Test observation already closed" {:code :test-observer-closed})))
    (if (>= (:assertions @state) (:max-assertions options))
      (swap! state update :problems conj :assertion-limit)
      (swap! state (fn [s] (-> s (update :assertions inc)
                               (update-in (if index [:executions index :counts event] [:unattributed event]) inc))))))
  nil)
(m/=> assertion! [:=> [:cat State Options [:maybe [:int {:min 0 :max 9999}]] [:enum :pass :fail :error]] :nil])

(defn- complete
  "Compare observed test membership with its independent pre-run inventory and retain collection failures.
   Only complete, nonvacuous, passing work earns coverage; no log summary supplies observed identity."
  [options declared state duration]
  (let [{:keys [executions unattributed problems]} state]
    (merge (select-keys options [:run :gate :label :source-digest])
           {:schema/version 1 :inventory declared :executions executions :unattributed unattributed
            :duration-ns duration}
           (test-graph/judge declared executions unattributed (vec problems)))))
(m/=> complete [:=> [:cat Options r/TestInventory StateValue c/Natural] r/TestObservation])

(defn- capture!
  "Observe a trusted synchronous runner against the independent roster of loaded namespaces.
   The runner invokes clojure.test itself, allowing cold checks and repeated runs within one capture.
   Dynamic test-var/report bindings preserve original reporters and fixture order. One record per
   invocation retains retries and nesting; assertion ownership follows dynamic bindings to joined
   futures. Fixture assertions are unattributed and prevent coverage. A thrown fixture/hook remains
   a runner error with missing declared tests, never a fabricated pass. Raw expected/actual values,
   exception messages and stack traces are not copied into observations; normal reporting remains.

   Limits bound retained invocations and assertion counters, not execution time or arbitrary test
   code. Use the process/container backend for deadlines and containment. Test-var intervals include
   test reporting and exclude surrounding fixtures. Detached assertions after return are unsupported
   and refuse. The caller supplies the judged-source digest; this observer does not attest file or
   toolchain immutability. No CPU, instruction or background measurements are inferred from elapsed
   time. The returned record is canonical EDN encodable and admitted as :test-observation."
  [options declared runner origin]
  (let [state (atom {:executions [] :attempts {} :assertions 0 :unattributed zero-counts :problems #{} :closed? false})
        original-test-var (or *test-var-delegate* test/test-var)
        original-report (or *report-delegate* test/report)]
    (try
      (binding [*invocation* nil
                *test-var-delegate* original-test-var *report-delegate* original-report
                test/test-var
                (fn [v]
                  (if-not (fn? (:test (meta v))) (original-test-var v)
                          (let [index (begin! state options v origin (when *invocation* (str "test-" *invocation*)))
                                returned? (volatile! false)]
                            (try
                              (binding [*invocation* index] (original-test-var v))
                              (vreset! returned? true)
                              (finally
                                (locking state
                                  (swap! state (fn [s] (-> s
                                                           (assoc-in [:executions index :returned?] @returned?)
                                                           (assoc-in [:executions index :interval :end-ns] (instant origin)))))))))))
                test/report
                (fn [event]
                  (when (contains? #{:pass :fail :error} (:type event))
                    (assertion! state options *invocation* (:type event)))
                  (original-report event))]
        (runner))
      (catch Throwable _ (swap! state update :problems conj :runner-error)))
    (locking state
      (swap! state assoc :closed? true)
      (complete options declared @state (instant origin)))))
(m/=> capture! [:=> [:cat Options r/TestInventory Runner clock/Tick] r/TestObservation])

(defn- declared!
  "Refuse inventories beyond the invocation budget before running any test or acquiring its clock."
  [options namespaces]
  (let [declared (inventory namespaces)]
    (when (> (count declared) (:max-tests options))
      (throw (ex-info "Test observation limit" {:code :test-limit})))
    declared))
(m/=> declared! [:=> [:cat Options Namespaces] r/TestInventory])

(defn run-with!
  "Observe a trusted synchronous runner against its independent inventory in a suite-local clock.
   See capture! for ownership, asynchronous-work, budget and source-attestation limits."
  [options namespaces runner]
  (let [declared (declared! options namespaces)]
    (capture! options declared runner (System/nanoTime))))
(m/=> run-with! [:=> [:cat Options Namespaces Runner] r/TestObservation])

(defn run-in!
  "Observe tests in a local clock and return its exact offset into one unchanged same-JVM context.
   The one sampled local origin is also used for every invocation/duration. No dispatch-envelope
   estimate, wall-clock adjustment or cross-JVM alignment substitutes for this anchor."
  [options namespaces runner context]
  (let [declared (declared! options namespaces) origin (System/nanoTime)]
    {:schema/version 1 :clock (:id context) :offset-ns (clock/at context origin)
     :observation (capture! options declared runner origin)}))
(m/=> run-in! [:=> [:cat Options Namespaces Runner clock/Context] r/ClockedTests])

(defn run!
  "Observe the ordinary clojure.test namespace runner with its original fixtures, hooks and reporters.
   See run-with! for acquisition, asynchronous-work and source-attestation limits."
  [options namespaces]
  (run-with! options namespaces (fn [] (apply test/run-tests namespaces) nil)))
(m/=> run! [:=> [:cat Options Namespaces] r/TestObservation])
