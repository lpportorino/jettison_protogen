(ns gate.attempt
  "Connect live input/output observations, cache receipts and one witnessed execution attempt.
   Reuse requires trusted runtime isolation evidence; mutable workspaces default to observation only."
  (:refer-clojure :exclude [run!])
  (:require [gate.cache :as cache]
            [gate.contract :as c]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.run-contract :as r]
            [gate.store :as store]
            [malli.core :as m]))

(def Request
  [:map {:closed true} [:directory inputs/Root] [:cache-directory inputs/Root]
   [:run c/Id] [:attempt c/Id] [:gate r/Gate] [:evidence inputs/Evidence]
   [:limits inputs/Limits] [:dependencies r/DependencyTerms]
   [:force? :boolean] [:isolation-verified? :boolean]])
(def DependencyObserver [:=> [:cat] r/DependencyTerms])
(def ObservedBackend [:=> [:cat r/Gate r/Snapshot coordinator/Cancellation] r/WorkResult])

(defn- invoke!
  "Execute only work, never accept a nested reuse claim; unexpected errors remain explicit failures."
  [backend gate before cancellation]
  (try
    (if @cancellation
      {:outcome :cancelled :coverage nil :reason :cancellation-requested}
      (let [work (coordinator/judged-result gate (backend gate before cancellation))]
        (if (= :cached (:outcome work))
          {:outcome :error :coverage nil :reason :invalid-adapter-result} work)))
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      {:outcome :cancelled :coverage nil :reason :cancellation-requested})
    (catch Throwable _ {:outcome :error :coverage nil :reason :adapter-exception})))
(m/=> invoke! [:=> [:cat ObservedBackend r/Gate r/Snapshot coordinator/Cancellation] r/WorkResult])

(defn- publish!
  "Receipt storage trouble does not rewrite a passing command into failed execution.
   Return the explicit publication outcome so reports can diagnose repeated uncacheable passes."
  [directory admission]
  (if (= :recorded (:status admission))
    (try (:status (store/publish! directory (:receipt admission)))
         (catch clojure.lang.ExceptionInfo error
           (case (:code (ex-data error)) :cache-conflict :conflict
                 :cache-invalid-receipt :invalid :io-error))
         (catch java.io.IOException _ :io-error)
         (catch SecurityException _ :io-error)
         (catch UnsupportedOperationException _ :io-error))
    :not-attempted))
(m/=> publish! [:=> [:cat inputs/Root r/Admission]
                [:enum :stored :existing :conflict :invalid :io-error :not-attempted]])

(defn- observe-dependencies
  "Missing, malformed or failed post-run dependency observation cannot certify a reusable pass."
  [gate observer]
  (try (let [value (observer)]
         (when (m/validate r/DependencyTerms value)
           (cache/validate-dependencies! gate value)
           value))
       (catch InterruptedException _ (.interrupt (Thread/currentThread)) nil)
       (catch Throwable _ nil)))
(m/=> observe-dependencies [:=> [:cat r/Gate DependencyObserver] [:maybe r/DependencyTerms]])

(defn run-observed!
  "Observe live inputs, explain reuse, execute when needed, and conditionally publish a receipt.
   Returns a closed AttemptObservation with separate work/admission/publication outcomes.
   Matching receipt/output/coverage is historical evidence; cached work never invokes backend.
   The backend owns independently observed coverage and must cooperate with cancellation.

   Request dependencies describe pre-run evidence; dependencies-after is called after actual
   execution to detect producer changes. Caller-supplied isolation-verified? is a trusted
   runtime attestation, not filesystem permission inference: the adapter must guarantee the
   declared environment, denied network and stable complete inputs. False disables both
   reuse and publication. observe! alone cannot supply this attestation. Forced runs still
   verify all admission conditions. No remote cache or stale output restoration is performed.

   Snapshot and output budgets are separate. Environment values and filenames in this record
   remain consumer-local; do not export a private consumer's record into a public repository."
  [{:keys [directory cache-directory gate evidence limits dependencies force? isolation-verified? run attempt] :as request}
   backend dependencies-after cancellation]
  (let [origin (System/nanoTime)
        before (inputs/observe! directory gate evidence limits)
        current-outputs (inputs/outputs! directory gate evidence limits)
        input-key (cache/input-key gate before dependencies)
        lookup (if (and isolation-verified? input-key (not force?) (= :content (:cache gate)) (= :denied (:network gate)))
                 (store/lookup cache-directory input-key) nil)
        decision (cond-> (cache/decide gate before dependencies (:receipt lookup) (or current-outputs []) force?)
                   (not isolation-verified?) (assoc :action :run :reason :unproven-isolation))
        base {:schema/version 1 :gate (:id gate) :run run :attempt attempt
              :before before :decision decision :dependencies-before dependencies
              :lookup (if lookup (if (= :hit (:status lookup)) :hit (:reason lookup)) :not-attempted)}]
    (if (and (= :cached (:action decision)) (not @cancellation))
      (assoc base :work {:outcome :cached :coverage (get-in lookup [:receipt :coverage]) :reason :cache-hit}
             :after nil :dependencies-after nil :outputs (or current-outputs []) :admission nil :publication :not-attempted
             :elapsed-ns (str (- (System/nanoTime) origin)))
      (let [work (invoke! backend gate before cancellation)
            after (inputs/observe! directory gate evidence limits)
            outputs (inputs/outputs! directory gate evidence limits)
            after-deps (observe-dependencies gate dependencies-after)
            admission (cond
                        (not isolation-verified?) {:status :refused :reason :unproven-isolation}
                        (not= :passed (:outcome work)) {:status :refused :reason :failed-execution}
                        (nil? after-deps) {:status :refused :reason :dependency-unknown}
                        (nil? outputs) {:status :refused :reason :outputs-changed}
                        :else (cache/admit gate before after dependencies after-deps (:outcome work)
                                           (:coverage work) outputs (:run request) (:attempt request)))
            publication (publish! cache-directory admission)]
        (cond-> (assoc base :work work :after after :dependencies-after after-deps
                       :outputs (or outputs []) :admission admission
                       :publication publication :elapsed-ns (str (- (System/nanoTime) origin)))
          (= :cancelled (:outcome work)) (assoc :decision (assoc decision :action :run :reason :cancellation-requested)))))))
(m/=> run-observed! [:=> [:cat Request ObservedBackend DependencyObserver coordinator/Cancellation] r/AttemptObservation])

(defn run!
  "Use a two-argument work backend. Runtime adapters needing the exact keyed pre-run
   snapshot must use run-observed!, whose backend receives (gate, before-snapshot, cancellation)."
  [request backend dependencies-after cancellation]
  (run-observed! request (fn [gate _ token] (backend gate token)) dependencies-after cancellation))
(m/=> run! [:=> [:cat Request coordinator/Backend DependencyObserver coordinator/Cancellation] r/AttemptObservation])
