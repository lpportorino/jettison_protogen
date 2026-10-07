(ns gate.process-batch
  "Source-bound native command DAGs with declared input snapshots and independent work witnesses."
  (:refer-clojure :exclude [run!])
  (:require [gate.batch-graph :as batch-graph]
            [gate.canonical :as canonical]
            [gate.clock :as clock]
            [gate.contract :as c]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.plan :as plan]
            [gate.process :as process]
            [gate.process-graph :as projection]
            [gate.run-contract :as r]
            [gate.runtime :as runtime]
            [gate.test-artifact :as artifact]
            [gate.verdict :as verdict]
            [malli.core :as m])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(def Witness
  "Trusted bounded callback (declaration, per-gate report directory, actual process observation).
   It independently reads observed checks/tests; returning the expected digest without evidence is invalid use."
  [:=> [:cat r/Gate inputs/Root r/ProcessObservation] [:maybe r/Coverage]])
(def Binding
  [:map {:closed true} [:directory inputs/Root] [:environment inputs/Environment]
   [:toolchains inputs/Toolchains] [:stdin [:maybe r/Path]]
   [:witness [:or [:= :command-invocation] Witness]]])
(def Bindings [:map-of {:min 1 :max 10000} c/Id Binding])
(def Options
  [:map {:closed true} [:run c/Id] [:key c/Id] [:label c/Label]
   [:coordinator r/CoordinatorOptions] [:input-limits inputs/Limits] [:process-limits process/Limits]])
(def Failure
  [:map {:closed true} [:code [:enum :process-binding :unsupported-process-policy :process-source-unavailable]]
   [:subject [:maybe c/Id]]])
(def State [:fn #(instance? clojure.lang.Atom %)])
(def WitnessResult [:map {:closed true} [:coverage [:maybe r/Coverage]] [:error? :boolean]])

(defn command-expectation
  "Declare exactly one command invocation, separately from internal checks and tests.
   Identity includes ordered argv boundaries and cwd. A launched command can satisfy this scope;
   process success still requires exit zero and known cleanup. It never proves internal test coverage."
  [command cwd]
  {:unit :commands :minimum 1
   :expected (runtime/terms-digest (into ["command-invocation-v1" cwd] command))})
(m/=> command-expectation [:=> [:cat [:vector {:min 1 :max 256} r/Text] [:or [:= "."] r/Path]] r/CoverageExpectation])

(defn- validate-bindings!
  "Require exact enrollment and effective environment/toolchain names before side effects; reject unsupported cache/isolation/output claims."
  [gates bindings]
  (when-not (= (set (map :id gates)) (set (keys bindings)))
    (throw (ex-info "Process enrollment refused" {:code :process-binding :subject nil})))
  (doseq [gate gates :let [adapter (get bindings (:id gate))]]
    (when (and (= :command-invocation (:witness adapter))
               (not= (:coverage gate) (command-expectation (:command gate) (:cwd gate))))
      (throw (ex-info "Command invocation scope refused" {:code :process-binding :subject (:id gate)})))
    (when (or (not= (set (:environment gate)) (set (keys (:environment adapter))))
              (not= (set (:toolchains gate)) (set (keys (:toolchains adapter)))))
      (throw (ex-info "Process environment/toolchain binding refused" {:code :process-binding :subject (:id gate)})))
    (when (or (not= :always (:cache gate)) (not= :allowed (:network gate)) (seq (:outputs gate)))
      (throw (ex-info "Unsupported native process policy" {:code :unsupported-process-policy :subject (:id gate)}))))
  nil)
(m/=> validate-bindings! [:=> [:cat r/Gates Bindings] :nil])

(defn- source
  "Preserve unavailable source identity as nil so post-start failures remain inspectable."
  [roots]
  (try (artifact/source-digest roots) (catch Exception _ nil)))
(m/=> source [:=> [:cat runtime/Paths] [:maybe c/Digest]])

(defn- input-digest
  "Issue identity only for complete snapshots containing any redirected stdin file."
  [snapshot stdin]
  (when (and (:complete? snapshot) (or (nil? stdin) (some #(= stdin (:path %)) (:files snapshot))))
    (canonical/sha256 (canonical/encode snapshot 67108864))))
(m/=> input-digest [:=> [:cat r/Snapshot [:maybe r/Path]] [:maybe c/Digest]])

(defn- witness
  "Run the independent callback only after a launch; callback failure never replaces raw process evidence."
  [adapter gate directory captured]
  (if-not (get-in captured [:observation :pid]) {:coverage nil :error? false}
          (try
            (let [coverage (if (= :command-invocation (:witness adapter))
                             (let [expectation (command-expectation (:command gate) (:cwd gate))]
                               {:unit :commands :expected (:expected expectation) :observed (:expected expectation) :count 1})
                             ((:witness adapter) gate directory (:observation captured)))]
              (if (m/validate [:maybe r/Coverage] coverage)
                {:coverage coverage :error? false} {:coverage nil :error? true}))
            (catch Throwable _ {:coverage nil :error? true}))))
(m/=> witness [:=> [:cat Binding r/Gate inputs/Root [:maybe r/ClockedProcess]] WitnessResult])

(defn- publish-capture!
  "Preserve per-gate publication failure in the enclosing report; never turn failed writes into a pass."
  [directory before after capture]
  (try
    (artifact/write-record! directory "inputs-before.edn" before :input-snapshot)
    (artifact/write-record! directory "inputs-after.edn" after :input-snapshot)
    (artifact/write-record! directory "process.edn" capture :process-capture)
    capture
    (catch Exception _ (assoc-in capture [:validation :publication-error?] true))))
(m/=> publish-capture! [:=> [:cat inputs/Root r/Snapshot r/Snapshot r/ProcessCapture] r/ProcessCapture])

(defn- execute!
  "Retain snapshots and the observed process even when work, source stability or witness acquisition fails."
  [options bindings roots directory expected context captures gate cancellation]
  (let [adapter (get bindings (:id gate))
        local (str directory "/gates/" (canonical/sha256 (:id gate)))
        _ (Files/createDirectory (Path/of local (make-array String 0)) (make-array FileAttribute 0))
        evidence (select-keys adapter [:environment :toolchains])
        before-source (source roots)
        before (inputs/observe! (:directory adapter) gate evidence (:input-limits options))
        before-digest (input-digest before (:stdin adapter))
        captured (when (and (= expected before-source) before-digest)
                   (process/run-clocked! {:directory (:directory adapter) :cwd (:cwd gate) :command (:command gate)
                                          :environment (:environment adapter) :stdin (:stdin adapter)
                                          :log-directory local :log "command.log" :limits (:process-limits options)}
                                         cancellation context))
        observed (witness adapter gate local captured)
        after (inputs/observe! (:directory adapter) gate evidence (:input-limits options))
        capture {:schema/version 1 :run (:run options) :gate (:id gate) :label (:label gate)
                 :source-digest expected :command (:command gate) :cwd (:cwd gate) :process captured
                 :coverage (:coverage observed)
                 :validation {:source-before before-source :source-after (source roots)
                              :inputs-before before-digest :inputs-after (input-digest after (:stdin adapter))
                              :coverage-error? (:error? observed) :publication-error? false}}
        capture (publish-capture! local before after capture)]
    (swap! captures conj capture)
    (let [result (verdict/process-capture-result gate capture)]
      (locking *out* (prn {:gate (:id gate) :outcome (:outcome result) :reason (:reason result)}))
      result)))
(m/=> execute! [:=> [:cat Options Bindings runtime/Paths inputs/Root c/Digest clock/Context State
                     r/Gate coordinator/Cancellation] r/WorkResult])

(defn run!
  "Run a native command DAG with closed records, source checks and independent per-gate work witnesses.
   Bindings must cover every declaration, including deselections. Requests use exact declared argv,
   cwd and environment; toolchain digests are caller-supplied evidence. All gates are always-run,
   network-allowed and have no published outputs. Use the contained backend for cache/isolation.

   Supply the actual immutable controller/adapter classpath as source roots and declare each gate's
   read inputs. Complete snapshots (including redirected stdin) are required before launch and must
   agree after command and witness acquisition. Missing/changed inputs, changed controller source,
   callback exceptions and process failures retain observed evidence but cannot pass. Equality is
   not proof of isolation or absence of edit/revert cycles. Witness callbacks must be bounded and
   cooperative; process limits do not forcibly stop arbitrary in-process callback code.

   A fresh report directory holds definitions.edn, batch.edn, graph.edn and report.edn containing all
   captures. Each gates/SHA256_ID directory holds bounded command.log, both input snapshots and
   process.edn. Publication is create-only, not transactional. Initial source failure refuses before
   execution; I/O failure may leave incomplete artifacts and must never be promoted to a full report."
  [options gates selected bindings source-roots output cancellation]
  (plan/schedule gates selected)
  (validate-bindings! gates bindings)
  (let [expected (or (source source-roots)
                     (throw (ex-info "Process controller source unavailable" {:code :process-source-unavailable :subject nil})))
        directory (artifact/output-directory! source-roots output)
        _ (Files/createDirectory (.resolve (Path/of directory (make-array String 0)) "gates") (make-array FileAttribute 0))
        context (clock/start!) captures (atom [])
        batch (coordinator/run-clocked! gates selected (:coordinator options)
                                        (partial execute! options bindings source-roots directory expected context captures)
                                        cancellation context)
        captured (vec (sort-by :gate @captures))
        graph (projection/project (assoc (select-keys options [:run :key :label]) :source-digest expected)
                                  gates selected batch captured)
        status (cond (= :cancelled (:status batch)) :cancelled
                     (and (= :passed (:status batch)) (get-in graph [:run :complete?])) :passed
                     :else :failed)
        report {:schema/version 1 :status status :batch batch :graph graph :captures captured}]
    (artifact/write-record! directory "definitions.edn" gates :gate-definitions)
    (artifact/write-record! directory "batch.edn" batch :coordinator-batch)
    (artifact/write-record! directory "graph.edn" graph :graph)
    (artifact/write-record! directory "report.edn" report :process-batch-report)
    (locking *out* (prn {:status status :gates (count (:dispatches batch)) :report (str directory "/report.edn")}))
    report))
(m/=> run! [:=> [:cat Options r/Gates batch-graph/Roots Bindings runtime/Paths inputs/Root coordinator/Cancellation]
            r/ProcessBatchReport])
