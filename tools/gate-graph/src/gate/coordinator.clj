(ns gate.coordinator
  "Bounded DAG dispatch with exclusive resource claims and coverage-checked adapter results.
   Owns scheduling, not subprocess isolation: adapters must cooperate with cancellation."
  (:refer-clojure :exclude [run!])
  (:require [gate.clock :as clock]
            [gate.contract :as c]
            [gate.plan :as plan]
            [gate.run-contract :as r]
            [gate.verdict :as verdict]
            [malli.core :as m])
  (:import [java.util.concurrent Callable ExecutorCompletionService Executors TimeUnit Future]))

(def Cancellation
  [:fn {:error/message "Expected a boolean cancellation atom"}
   #(and (instance? clojure.lang.Atom %) (boolean? @%))])
(def Backend
  "Trusted local adapter, passed a declaration and cooperative cancellation token.
   Its returned data is checked explicitly, including expected observed coverage."
  [:=> [:cat r/Gate Cancellation] r/WorkResult])
(def Clock clock/Context)
(def Results [:map-of {:max 10000} c/Id r/Dispatch])
(def Active [:map-of {:max 256} c/Id [:fn #(instance? Future %)]])
(def Failure
  [:map {:closed true} [:code [:enum :invalid-resource-claims :scheduler-stalled]]])

(defn- now
  "Elapsed monotonic nanoseconds in this coordinator invocation's clock domain."
  [origin]
  (clock/now origin))
(m/=> now [:=> [:cat Clock] c/Natural])

(defn- failure
  "Compact failed adapter result never embeds exception messages or rejected values."
  [reason]
  {:outcome :error :coverage nil :reason reason})
(m/=> failure [:=> [:cat [:enum :adapter-exception :invalid-adapter-result :coverage-mismatch]] r/WorkResult])

(defn judged-result
  "A declared success needs the expected inventory digest and nonvacuous observed work.
   Cache coverage is historical work supplied by the reuse adapter, never new execution."
  [gate result]
  (verdict/judge gate result))
;; The local callback's untrusted return is intentionally admitted here. A schema
;; predicate accepting every value is confined to this validation boundary.
(m/=> judged-result [:=> [:cat r/Gate [:fn (constantly true)]] r/WorkResult])

(defn- dispatch!
  "Invoke one adapter, converting unexpected assertions/exceptions into failure.
   The interval measures the whole adapter (including cache checks); it is not
   a command execution span. Adapters emit their actual child/test spans separately."
  [backend gate cancellation origin]
  (let [start (now origin)
        work (try
               (if @cancellation
                 {:outcome :cancelled :coverage nil :reason :cancellation-requested}
                 (judged-result gate (backend gate cancellation)))
               (catch InterruptedException _
                 (.interrupt (Thread/currentThread))
                 {:outcome :cancelled :coverage nil :reason :cancellation-requested})
               (catch Throwable _ (failure :adapter-exception)))
        end (now origin)]
    {:gate (:id gate) :outcome (:outcome work) :at-ns end
     :adapter-interval {:start-ns start :end-ns end} :work work :blocked-by []}))
(m/=> dispatch! [:=> [:cat Backend r/Gate Cancellation Clock] r/Dispatch])

(defn- decision
  "Undispatched work has a decision instant and no invented execution interval."
  [id outcome blocked origin]
  {:gate id :outcome outcome :at-ns (now origin) :adapter-interval nil :work nil :blocked-by blocked})
(m/=> decision [:=> [:cat c/Id [:enum :blocked :deselected :cancelled]
                     [:vector {:max 1024} c/Id] Clock] r/Dispatch])

(defn- execution-dependencies
  "Invalidation-only relationships affect keys but do not wait for execution."
  [gate]
  (filterv #(not= :invalidates (:relation %)) (:dependencies gate)))
(m/=> execution-dependencies [:=> [:cat r/Gate] [:vector {:max 1024} r/Dependency]])

(defn- blocked-by
  "Ordering-only dependencies need termination, while requires/produces need success."
  [gate results]
  (->> (:dependencies gate)
       (filter #(contains? #{:requires :produces} (:relation %)))
       (keep (fn [{:keys [gate]}]
               (when-let [result (get results gate)]
                 (when-not (contains? #{:passed :cached} (:outcome result)) gate))))
       distinct sort vec))
(m/=> blocked-by [:=> [:cat r/Gate Results] [:vector {:max 1024} c/Id]])

(defn- ready?
  "Require completed predecessors and available exclusive resources before dispatch."
  [gate results claims active]
  (and (every? #(contains? results (:gate %)) (execution-dependencies gate))
       (empty? (blocked-by gate results))
       (not-any? (set (mapcat #(get claims %) (keys active))) (get claims (:id gate)))))
(m/=> ready? [:=> [:cat r/Gate Results (second (nth r/CoordinatorOptions 3)) Active] :boolean])

(defn- validate-claims!
  "Unknown gates and duplicate claims are refused before any adapter runs."
  [index options]
  (when (some (fn [[id claims]] (or (not (contains? index id)) (not (plan/unique? claims)))) (:claims options))
    (throw (ex-info "Coordinator refused resource claims" {:code :invalid-resource-claims}))))
(m/=> validate-claims! [:=> [:cat r/GateIndex r/CoordinatorOptions] :nil])

(defn- propagate
  "Resolve failed prerequisites and cancellation without consuming worker slots."
  [index pending results cancellation origin]
  (reduce (fn [[waiting complete] id]
            (let [blocked (blocked-by (get index id) complete)]
              (cond
                @cancellation [waiting (assoc complete id (decision id :cancelled [] origin))]
                (seq blocked) [waiting (assoc complete id (decision id :blocked blocked origin))]
                :else [(conj waiting id) complete])))
          [[] results] pending))
(m/=> propagate [:=> [:cat r/GateIndex [:vector {:max 10000} c/Id] Results Cancellation Clock]
                 [:tuple [:vector {:max 10000} c/Id] Results]])

(defn- submit-ready!
  "Only submit up to available slots; claimed resources stay held until adapter completion."
  [completion backend index pending results active options cancellation origin]
  (reduce (fn [[waiting running] id]
            (if (and (not @cancellation) (< (count running) (:jobs options))
                     (ready? (get index id) results (:claims options) running))
              [waiting (assoc running id
                              (.submit ^ExecutorCompletionService completion
                                       ^Callable (bound-fn [] (dispatch! backend (get index id) cancellation origin))))]
              [(conj waiting id) running]))
          [[] active] pending))
(m/=> submit-ready!
      [:=> [:cat [:fn #(instance? ExecutorCompletionService %)] Backend r/GateIndex
            [:vector {:max 10000} c/Id] Results Active r/CoordinatorOptions Cancellation Clock]
       [:tuple [:vector {:max 10000} c/Id] Active]])

(defn run-clocked!
  "Run the selected declaration closure with a bounded adapter pool and exclusive claims.
   Returns a closed Batch in deterministic topological order, including deselections,
   prerequisite blocks and cancellations. Zero exit alone is insufficient: passed/cached
   adapter results must carry matching expected/observed coverage and minimum work.

   Options supply jobs (1..256) and gate-to-exclusive-resource claims. The backend receives
   (gate, boolean cancellation atom); it must stop its work and join children when asked.
   Interrupting the calling thread requests cancellation, waits for cooperative cleanup,
   then restores the interrupt flag. No new work starts after cancellation is observed.
   This API cannot forcibly stop arbitrary in-process code, System/exit, native hangs or
   detached subprocesses. Such gates require an isolated process/container backend.

   Adapter intervals measure dispatch work, including reuse checks, and must not be
   imported as execution spans for cached gates. The backend owns actual process/test
   instrumentation, independent coverage evidence, bounded logs and cache validation."
  [gates roots options backend cancellation context]
  (let [schedule (plan/schedule gates roots)
        index (into {} (map (juxt :id identity) gates))
        _ (validate-claims! index options)
        origin context
        _ (clock/now context)
        executor (Executors/newFixedThreadPool (:jobs options))
        completion (ExecutorCompletionService. executor)
        interrupted? (atom false)]
    (try
      (loop [pending (:selected schedule) active {}
             results (into {} (map (fn [id] [id (decision id :deselected [] origin)]) (:deselected schedule)))]
        (let [[pending results] (propagate index pending results cancellation origin)
              [pending active] (submit-ready! completion backend index pending results active options cancellation origin)]
          (cond
            (and (empty? pending) (empty? active))
            {:schema/version 1 :schedule schedule :dispatches (mapv results (:order schedule))
             :duration-ns (now origin) :clock (:id context)
             :status (cond @cancellation :cancelled
                           (every? #(contains? #{:passed :cached :deselected} (:outcome %)) (vals results)) :passed
                           :else :failed)}

            (empty? active)
            (if @cancellation (recur pending active results)
                (throw (ex-info "Coordinator has no runnable work" {:code :scheduler-stalled})))

            :else
            (let [finished (try (.poll completion 50 TimeUnit/MILLISECONDS)
                                (catch InterruptedException _
                                  (reset! interrupted? true) (reset! cancellation true) nil))]
              (if finished
                (let [result (.get ^Future finished)]
                  (recur pending (dissoc active (:gate result)) (assoc results (:gate result) result)))
                (recur pending active results))))))
      (catch Throwable error
        (reset! cancellation true)
        (throw error))
      (finally
        (.shutdown executor)
        (loop []
          (when-not (try (.awaitTermination executor 50 TimeUnit/MILLISECONDS)
                         (catch InterruptedException _ (reset! interrupted? true) false))
            (recur)))
        (when @interrupted? (.interrupt (Thread/currentThread)))))))
(m/=> run-clocked! [:=> [:cat r/Gates [:vector {:max 10000} c/Id] r/CoordinatorOptions Backend Cancellation Clock] r/Batch])

(defn run!
  "Run the declaration closure using a fresh one-JVM monotonic domain.
   Use run-clocked! with one unchanged gate.clock context to anchor adapter observations into a graph."
  [gates roots options backend cancellation]
  (run-clocked! gates roots options backend cancellation (clock/start!)))
(m/=> run! [:=> [:cat r/Gates [:vector {:max 10000} c/Id] r/CoordinatorOptions Backend Cancellation] r/Batch])
