(ns gate.contained
  "Connect a keyed attempt to copied-input execution, independent work coverage and verified outputs."
  (:refer-clojure :exclude [run!])
  (:require [gate.attempt :as attempt]
            [gate.canonical :as canonical]
            [gate.container :as container]
            [gate.contract :as c]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.ownership :as ownership]
            [gate.process :as process]
            [gate.publish :as publish]
            [gate.run-contract :as r]
            [gate.runtime :as runtime]
            [malli.core :as m]))

(def Options
  [:map {:closed true} [:cache-directory inputs/Root] [:run c/Id] [:attempt c/Id]
   [:dependencies r/DependencyTerms] [:force? :boolean]
   [:ownership-timeout-ms {:optional true} [:int {:min 1 :max 86400000}]]])
(def CoverageObserver [:=> [:cat r/Gate container/Result] [:maybe r/Coverage]])
(def Result
  [:map {:closed true} [:directory [:maybe inputs/Root]] [:observation r/ContainedObservation]])
(def Completion
  [:map {:closed true} [:work r/WorkResult] [:publication r/OutputPublication]])

(defn- witnessed-work
  "Require a known, removed container and successful process before asking for independent coverage."
  [gate result observer cancellation]
  (let [{:keys [status container removed? execution]} (:observation result)]
    (cond
      (or @cancellation (= :cancelled status)) {:outcome :cancelled :coverage nil :reason :cancellation-requested}
      (or (not= :exited status) (nil? container) (not removed?) (nil? execution))
      {:outcome :error :coverage nil :reason :adapter-exception}
      :else (let [preliminary (process/work-result gate execution nil)]
              (if (= :coverage-mismatch (:reason preliminary))
                (process/work-result gate execution (observer gate result))
                preliminary)))))
(m/=> witnessed-work [:=> [:cat r/Gate container/Result CoverageObserver coordinator/Cancellation] r/WorkResult])

(defn complete!
  "Judge container work and publish only outputs belonging to the exact keyed pre-run snapshot.
   The coverage observer must inspect actual execution evidence; it cannot infer work from exit zero.
   Requires exclusive ownership of the declared output paths through outer attempt admission.
   A passing child with refused/partial publication is an error for dependent gates, with its raw
   execution retained separately. Workspace changes or a different copied snapshot never publish.
   Cancellation before publication preserves old outputs. Publication itself is a bounded file batch,
   not a transaction, and callers must inspect its explicit partial outcome."
  [request before result observer cancellation]
  (let [gate (:gate request)
        work (witnessed-work gate result observer cancellation)
        skipped {:status :not-attempted :installed [] :reason nil}]
    (cond
      (not= :passed (:outcome work)) {:work work :publication skipped}
      @cancellation {:work {:outcome :cancelled :coverage nil :reason :cancellation-requested} :publication skipped}
      (or (not (:complete? before))
          (not= before (get-in result [:observation :snapshot]))
          (not= before (inputs/observe! (:directory request) gate (:evidence request) (:input-limits request))))
      {:work {:outcome :error :coverage nil :reason :input-unstable}
       :publication {:status :refused :installed [] :reason :source-changed}}
      (nil? (get-in result [:observation :outputs]))
      {:work {:outcome :error :coverage nil :reason :output-publication}
       :publication {:status :refused :installed [] :reason :output-verification}}
      :else
      (let [publication (publish/install! (str (:directory result) "/outputs") (:directory request) gate
                                          (:evidence request) (:input-limits request) (get-in result [:observation :outputs]))]
        {:work (if (= :installed (:status publication)) work
                   {:outcome :error :coverage nil :reason :output-publication})
         :publication publication}))))
(m/=> complete! [:=> [:cat container/Request r/Snapshot container/Result CoverageObserver coordinator/Cancellation] Completion])

(defn- keyed-request
  "Add verified runtime identity to the gate's actual cache material; reserve the term from callers."
  [request observation]
  (when (some #{"gate-runtime"} (get-in request [:gate :toolchains]))
    (throw (ex-info "Reserved runtime toolchain term" {:code :runtime-policy})))
  (if (= :verified (:status observation))
    (-> request
        (update-in [:gate :toolchains] conj "gate-runtime")
        (assoc-in [:evidence :toolchains "gate-runtime"] (canonical/sha256 (canonical/encode (:identity observation) 65536))))
    request))
(m/=> keyed-request [:=> [:cat container/Request r/RuntimeObservation] container/Request])

(defn- run-owned!
  "Keep claims across runtime observation, lookup, execution, copyback and receipt publication."
  [request options observer dependencies-after cancellation]
  (let [before (:observation (runtime/probe! request cancellation))
        effective (keyed-request request before)
        after (atom nil) result (atom nil) publication (atom {:status :not-attempted :installed [] :reason nil})
        attempt-request (merge (dissoc options :ownership-timeout-ms) (select-keys effective [:directory :gate :evidence])
                               {:limits (:input-limits effective) :isolation-verified? (= :verified (:status before))})
        observation (attempt/run-observed!
                     attempt-request
                     (fn [_ snapshot token]
                       (let [executed (container/run! effective token)
                             _ (reset! result executed)
                             witnessed (fn [gate execution]
                                         (let [coverage (observer gate execution)]
                                           (when (= :verified (:status before))
                                             (reset! after (:observation (runtime/probe! request token))))
                                           (when (or (not= :verified (:status before))
                                                     (= (:identity before) (:identity @after))) coverage)))
                             completion (complete! effective snapshot executed witnessed token)
                             completion (if (and @after (not= (:identity before) (:identity @after)))
                                          (assoc completion :work {:outcome (if @token :cancelled :error) :coverage nil
                                                                   :reason (if @token :cancellation-requested :runtime-unstable)})
                                          completion)]
                         (reset! publication (:publication completion))
                         (:work completion)))
                     dependencies-after cancellation)]
    {:directory (:directory @result)
     :observation {:schema/version 1 :attempt observation :container (:observation @result)
                   :runtime-before before :runtime-after @after :output-publication @publication}}))
(m/=> run-owned! [:=> [:cat container/Request Options CoverageObserver attempt/DependencyObserver coordinator/Cancellation] Result])

(defn run!
  "Run one contained attempt and retain separate command, output-publication and receipt evidence.
   The exact pre-run snapshot is passed from attempt/run-observed! into completion verification.
   Returned directory identifies retained local logs/copies; the portable observation omits that path.
   Takes cooperative workspace output claims across the full attempt, including receipt admission.
   Runtime identity binds controller/JDK/classpath/client bytes, daemon, machine and resource policy;
   unavailable identity runs uncached. After execution, a changed/unavailable identity prevents
   publication. Loaded controller code must remain immutable and all output writers must cooperate.
   Coverage observers must independently inspect actual work; receipts retain historical coverage."
  [request options observer dependencies-after cancellation]
  (let [origin (System/nanoTime)
        lease (ownership/acquire! (:directory request) (get-in request [:gate :outputs])
                                  (get options :ownership-timeout-ms 600000) cancellation)
        waited (str (- (System/nanoTime) origin))]
    (try
      (update (run-owned! request options observer dependencies-after cancellation) :observation
              assoc :ownership-wait-ns waited :elapsed-ns (str (- (System/nanoTime) origin)))
      (finally (ownership/release! lease)))))
(m/=> run! [:=> [:cat container/Request Options CoverageObserver attempt/DependencyObserver coordinator/Cancellation] Result])
