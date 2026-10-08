(ns gate.archive-run
  "Bind an actual run callback to before/after repository evidence and publish its complete archive."
  (:refer-clojure :exclude [run!])
  (:require [gate.archive :as archive]
            [gate.archive-contract :as ac]
            [gate.archive-io :as publication]
            [gate.contract :as c]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.repository :as repository]
            [gate.repository-contract :as rc]
            [gate.repository-git :as git]
            [gate.repository-identity :as identity]
            [gate.viewer-asset :as viewer]
            [malli.core :as m]))

(def Options
  [:map {:closed true} [:repository inputs/Root] [:policy rc/Policy] [:git git/Settings]
   [:limits repository/Limits] [:scope ac/Scope] [:output inputs/Root]])
(def Acquired
  [:map {:closed true} [:acquisition ac/Acquisition] [:observation [:maybe rc/Observation]]])
(def Failure
  [:multi {:dispatch :code}
   [:archive-not-passed
    [:map {:closed true} [:code [:= :archive-not-passed]] [:status ac/Status] [:artifact c/Digest]]]
   [:archive-cancelled
    [:map {:closed true} [:code [:= :archive-cancelled]] [:status [:= :passed]] [:artifact c/Digest]]]])

(defn- acquire!
  "Retain only recognized closed acquisition refusals; programming errors still propagate."
  [options cancellation]
  (try (let [observation (repository/observe! (:repository options) (:policy options) (:git options)
                                              (:limits options) cancellation)]
         {:acquisition {:state :observed :snapshot (identity/summarize observation)} :observation observation})
       (catch clojure.lang.ExceptionInfo error
         (if (m/validate rc/Failure (ex-data error))
           {:acquisition {:state :unavailable :failure (ex-data error)} :observation nil}
           (throw error)))))
(m/=> acquire! [:=> [:cat Options coordinator/Cancellation] Acquired])

(defn run!
  "Observe before, call the actual runner once, observe after, then retain provenance and publish HTML/EDN.
   The callback receives the same cancellation token and returns its validated graph. It owns gate
   execution and must create the fresh output directory, normally through the shared batch runner.
   Call this inside the CLI shutdown callback so cancellation also reaches provenance acquisition.

   Recognized provenance failures remain visible and cannot earn a passing archive. The callback
   still runs and must honor cancellation itself. Callback/programming/publication exceptions
   propagate; no fabricated graph or successful archive replaces them. Callers must gate a successful
   process exit/cache signature with require-passed! after preserving the actual command exit status.
   A final token sample after publication refuses a passing return with :archive-cancelled.
   Already published evidence remains immutable and describes observations completed before cancellation;
   the failure identifies that archive. Failed/cancelled evidence retains its original disposition.
   Cancellation after this final sample belongs to the caller's next action, not an atomic filesystem promise.
   Full private inventories stay beside the run; deliberate export carries compact checked headers."
  [options asset cancellation runner]
  (let [before (acquire! options cancellation)
        graph (runner cancellation)
        after (acquire! options cancellation)
        document (archive/create (:scope options) graph (:acquisition before) (:acquisition after))]
    (doseq [[phase evidence] [[:before before] [:after after]] :when (:observation evidence)]
      (publication/write-observation! (:output options) phase (:observation evidence)))
    (publication/publish! (:output options) document asset)
    (when (and (= :passed (:status document)) @cancellation)
      (throw (ex-info "Archive lifecycle cancelled after observations completed"
                      {:code :archive-cancelled :status :passed :artifact (:artifact document)})))
    document))
(m/=> run! [:=> [:cat Options viewer/Asset coordinator/Cancellation
                 [:=> [:cat coordinator/Cancellation] c/Graph]] ac/Document])

(defn require-passed!
  "Prevent a successful command/signature when archive provenance is changed, missing or cancelled.
   Call after publication and only when the underlying runner would otherwise return success."
  [document]
  (archive/require-valid! document)
  (when-not (= :passed (:status document))
    (throw (ex-info "Run archive did not pass"
                    {:code :archive-not-passed :status (:status document) :artifact (:artifact document)})))
  nil)
(m/=> require-passed! [:=> [:cat ac/Document] :nil])
