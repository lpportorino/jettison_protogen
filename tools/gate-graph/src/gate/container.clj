(ns gate.container
  "Owned local Docker execution over copied read-only inputs and isolated writable output roots."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.process :as process]
            [gate.run-contract :as r]
            [gate.snapshot :as snapshot]
            [malli.core :as m])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util HexFormat UUID]))

(def default-limits {:pids 1024 :memory-mib 2048 :tmp-mib 256 :cpus 2})
(def Request
  [:map {:closed true} [:directory inputs/Root] [:scratch-parent inputs/Root]
   [:docker inputs/Root] [:socket inputs/Root] [:image c/Digest]
   [:gate r/Gate] [:evidence inputs/Evidence] [:input-limits inputs/Limits]
   [:process-limits process/Limits] [:limits r/ContainerLimits] [:output-roots snapshot/OutputRoots]])
(def Context [:map {:closed true} [:directory inputs/Root] [:docker inputs/Root] [:socket inputs/Root]])
(def Step [:enum :image :create :start :remove])
(def Result [:map {:closed true} [:directory inputs/Root] [:observation r/ContainerObservation]])

(defn engine-digest
  "Conservatively fingerprint all bundled JVM/CLJC engine sources, including the runtime adapters.
   Resources must be present; each read is bounded to 1 MiB. Source-only/AOT builds must ship them."
  []
  (let [names ["admission.cljc" "archive.cljc" "archive_contract.cljc" "archive_io.clj" "archive_run.clj" "attempt.clj" "batch_graph.cljc" "cache.cljc" "canonical.cljc"
               "clock.clj" "clojure_test.clj" "contained.clj" "container.clj" "contract.cljc"
               "coordinator.clj" "decimal.cljc" "diagnostic.clj" "diff.cljc" "graph.cljc" "inputs.clj" "inspection_contract.cljc"
               "interval.cljc" "measure.cljc" "ownership.clj" "plan.cljc" "process.clj" "process_batch.clj" "process_graph.cljc" "publish.clj"
               "query.cljc" "report.clj" "report_io.clj" "report_publish.clj" "repository.clj" "repository_contract.cljc" "repository_git.clj" "repository_identity.cljc" "run_contract.cljc" "runtime.clj" "schema.cljc" "snapshot.clj" "store.clj"
               "test_artifact.clj" "test_batch.clj" "test_graph.cljc" "trace_cli.clj" "trace_contract.clj"
               "trace_import.clj" "trace_io.clj" "verdict.cljc" "viewer_asset.clj" "viewer_build.clj" "viewer_contract.cljc"]]
    (canonical/sha256
     (str/join "\n" (map (fn [resource-name]
                           (with-open [stream (io/input-stream (or (io/resource (str "gate/" resource-name))
                                                                   (throw (ex-info "Missing engine source" {:code :container-policy}))))]
                             (let [source-bytes (.readNBytes stream 1048577)]
                               (when (> (alength source-bytes) 1048576) (throw (ex-info "Engine source limit" {:code :container-policy})))
                               (str resource-name ":" (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") source-bytes)))))) names)))))
(m/=> engine-digest [:=> [:cat] c/Digest])

(defn profile
  "Key image, engine bytes, controller kernel/architecture, resource policy and output mount roots.
   Requires a trusted Linux daemon on this same kernel/filesystem, not Docker Desktop or a remote proxy."
  [image limits output-roots]
  {:schema/version 1 :backend :docker-readonly-v1 :image image :engine (engine-digest)
   :kernel (System/getProperty "os.version") :architecture (System/getProperty "os.arch")
   :limits limits :output-roots (vec (sort output-roots))})
(m/=> profile [:=> [:cat c/Digest r/ContainerLimits snapshot/OutputRoots] r/ContainerProfile])

(defn runtime-evidence
  "Build keyed image/profile terms and select exactly the gate's declared effective environment.
   Other toolchain terms remain caller-supplied; their completeness is checked during observation."
  [gate runtime-profile environment toolchains]
  {:environment (select-keys environment (:environment gate))
   :toolchains (assoc toolchains "image" (:image runtime-profile)
                      "container-profile" (canonical/sha256 (canonical/encode runtime-profile 65536)))})
(m/=> runtime-evidence [:=> [:cat r/Gate r/ContainerProfile inputs/Environment inputs/Toolchains] inputs/Evidence])

(defn policy-valid?
  "Require a denied-network, explicitly keyed profile, exact environment and plain local mount paths."
  [request runtime-profile]
  (let [{:keys [gate evidence docker socket directory scratch-parent output-roots]} request]
    (and (= "Linux" (System/getProperty "os.name"))
         (= :denied (:network gate))
         (every? (set (:toolchains gate)) ["image" "container-profile"])
         (= (:image request) (get-in evidence [:toolchains "image"]))
         (= (canonical/sha256 (canonical/encode runtime-profile 65536)) (get-in evidence [:toolchains "container-profile"]))
         (= (set (:environment gate)) (set (keys (:environment evidence))))
         (not (re-find #"--jobserver-(?:auth|fds)=" (or (get-in evidence [:environment "MAKEFLAGS"]) "")))
         (every? #(and (str/starts-with? % "/") (not (re-find #"[,\r\n\u0000]" %))) [docker socket directory scratch-parent])
         (not-any? #(re-find #"[,\r\n]" %) output-roots))))
(m/=> policy-valid? [:=> [:cat Request r/ContainerProfile] :boolean])

(defn- invoke!
  "Execute one Docker CLI phase with bounded logs and explicit endpoint/config, no inherited environment."
  [context step arguments limits cancellation]
  (process/run! {:directory (:directory context) :cwd "."
                 :command (into [(:docker context) "--host" (str "unix://" (:socket context))
                                 "--config" (str (:directory context) "/docker-config")] arguments)
                 :environment {} :stdin nil :log-directory (:directory context)
                 :log (str (name step) ".log") :limits limits} cancellation))
(m/=> invoke! [:=> [:cat Context Step [:vector {:min 1 :max 1024} process/Argument] process/Limits coordinator/Cancellation] r/ProcessObservation])

(defn- successful?
  "A completed CLI exit is protocol evidence only, never gate work coverage."
  [observation]
  (and (= :exited (:status observation)) (= 0 (:exit observation))
       (not (:cleanup-required? observation)) (:observed-processes-stopped? observation)))
(m/=> successful? [:=> [:cat r/ProcessObservation] :boolean])

(defn- phase-text
  "Read a small protocol log only after bounded successful execution."
  [context step]
  (let [path (Path/of (str (:directory context) "/" (name step) ".log") (make-array String 0))]
    (when (> (Files/size path) 4096) (throw (ex-info "Docker protocol size" {:code :container-policy})))
    (str/trim (Files/readString path))))
(m/=> phase-text [:=> [:cat Context Step] [:string {:max 4096}]])

(defn create-command
  "Construct the fixed Docker profile. Source is read-only; only declared output roots and tmpfs are writable.
   Entrypoint env -i removes image defaults; names/values and command remain argv elements.
   No host Docker socket, network, devices or home directory enter the child."
  [request context container-name]
  (let [{:keys [gate evidence image limits output-roots]} request
        source (str (:directory context) "/source")
        output (str (:directory context) "/outputs")]
    (vec (concat
          ["create" "--name" container-name "--pull" "never" "--network" "none" "--read-only"
           "--cap-drop" "ALL" "--security-opt" "no-new-privileges" "--user" "0:0"
           "--hostname" "gate" "--no-healthcheck" "--log-driver" "none"
           "--pids-limit" (str (:pids limits)) "--memory" (str (:memory-mib limits) "m")
           "--memory-swap" (str (:memory-mib limits) "m") "--cpus" (str (:cpus limits))
           "--tmpfs" (str "/tmp:rw,nosuid,nodev,noexec,size=" (:tmp-mib limits) "m")
           "--shm-size" "16777216" "--ipc" "private" "--cgroupns" "private"
           "--mount" (str "type=bind,source=" source ",target=/gate,readonly,bind-recursive=disabled")
           "--workdir" (if (= "." (:cwd gate)) "/gate" (str "/gate/" (:cwd gate)))
           "--entrypoint" "/usr/bin/env"]
          (mapcat (fn [root] ["--mount" (str "type=bind,source=" output "/" root ",target=/gate/" root ",bind-recursive=disabled")]) output-roots)
          [(str "sha256:" image) "-i"]
          (map (fn [[k v]] (str k "=" v)) (sort-by key (remove (comp nil? val) (:environment evidence))))
          (:command gate)))))
(m/=> create-command [:=> [:cat Request Context process/FileName] [:vector {:min 1 :max 1024} process/Argument]])

(defn- execute!
  "Create before start so cancellation/removal cannot race a second container creation.
   Remove by the owned random name even when create is ambiguous; failed removal stays unknown."
  [request context runtime-profile cancellation]
  (let [small-limits (assoc (:process-limits request) :timeout-ms 10000 :log-bytes 4096)
        container-name (str "gate-" (UUID/randomUUID))
        base {:schema/version 1 :profile runtime-profile :container nil :snapshot nil :execution nil
              :status :refused :reason nil :removed? true :outputs nil :elapsed-ns "0"}
        copied (snapshot/materialize! (:directory request) (str (:directory context) "/source")
                                      (:gate request) (:evidence request) (:input-limits request) (:output-roots request))
        base (assoc base :snapshot (:snapshot copied))
        created? (atom false) removal (atom nil)
        body (try
               (Files/createDirectory (Path/of (str (:directory context) "/outputs") (make-array String 0)) (make-array FileAttribute 0))
               (doseq [root (:output-roots request)]
                 (Files/createDirectories (Path/of (str (:directory context) "/outputs/" root) (make-array String 0))
                                          (make-array FileAttribute 0)))
               (let [image-check (invoke! context :image ["image" "inspect" "--format" "{{.Id}} {{.Os}} {{json (index .Config \"Volumes\")}}"
                                                          (str "sha256:" (:image request))] small-limits cancellation)]
                 (if-not (and (successful? image-check)
                              (contains? #{(str "sha256:" (:image request) " linux null")
                                           (str "sha256:" (:image request) " linux {}")} (phase-text context :image)))
                   (assoc base :reason :container-image)
                   (do
                     (Files/writeString (Path/of (str (:directory context) "/container-name") (make-array String 0)) container-name
                                        (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE_NEW java.nio.file.StandardOpenOption/WRITE]))
                     (reset! created? true)
                     (let [creation (invoke! context :create (create-command request context container-name) small-limits cancellation)]
                       (if-not (and (successful? creation) (m/validate c/Digest (phase-text context :create)))
                         (assoc base :status :runtime-failed :reason :container-create :removed? false)
                         (let [id (phase-text context :create)
                               execution (invoke! context :start ["start" "--attach" id] (:process-limits request) cancellation)]
                           (assoc base :container id :execution execution :removed? false
                                  :status (case (:status execution) :cancelled :cancelled :exited :exited :runtime-failed)
                                  :reason (when-not (contains? #{:exited :cancelled} (:status execution)) :container-start)
                                  :outputs (when (successful? execution)
                                             (inputs/outputs! (str (:directory context) "/outputs") (:gate request)
                                                              (:evidence request) (:input-limits request))))))))))
               (finally
                 (when @created?
                   ;; Cleanup has its own token and clears/restores a caller interrupt.
                   (let [interrupted? (Thread/interrupted)]
                     (try (reset! removal (invoke! context :remove ["rm" "--force" "--volumes" container-name] small-limits (atom false)))
                          (finally (when interrupted? (.interrupt (Thread/currentThread)))))))))]
    (if-not @created? body
            (if (and (:container body) (successful? @removal)) (assoc body :removed? true)
                (assoc body :status :cleanup-unknown :reason :container-cleanup :removed? false)))))
(m/=> execute! [:=> [:cat Request Context r/ContainerProfile coordinator/Cancellation] r/ContainerObservation])

(defn run!
  "Execute a declared gate in one owned local Docker container over verified copied inputs.
   Scratch parent must exist on the same filesystem visible at identical paths to the daemon.
   The image is an immutable SHA-256 ID already present locally; image-declared volumes refuse.
   A new random attempt directory retains source, outputs and bounded phase logs for inspection.
   Only declared regular outputs are observed; nothing is copied back to the caller's workspace.
   Removal must succeed before a completed result can be trusted. Unknown cleanup never claims success.
   The caller owns recovery of retained scratch/ambiguous create attempts and deterministic gate
   semantics (clock/random/system-call inputs are not virtualized). No remote daemon or arbitrary
   Docker option passthrough is supported. This API does not itself publish a cache receipt."
  [request cancellation]
  (let [origin (System/nanoTime) runtime-profile (profile (:image request) (:limits request) (:output-roots request))
        root (Files/createTempDirectory (Path/of (:scratch-parent request) (make-array String 0)) "container-" (make-array FileAttribute 0))
        context {:directory (str root) :docker (:docker request) :socket (:socket request)}
        base {:schema/version 1 :profile runtime-profile :container nil :snapshot nil :execution nil
              :status :refused :reason :container-policy :removed? true :outputs nil :elapsed-ns "0"}
        observation (try
                      (cond @cancellation (assoc base :status :cancelled :reason nil)
                            (not (policy-valid? request runtime-profile)) base
                            :else (execute! request context runtime-profile cancellation))
                      (catch clojure.lang.ExceptionInfo error
                        (if (m/validate snapshot/Failure (ex-data error)) (assoc base :reason (:code (ex-data error)))
                            (throw error))))]
    {:directory (str root) :observation (assoc observation :elapsed-ns (str (- (System/nanoTime) origin)))}))
(m/=> run! [:=> [:cat Request coordinator/Cancellation] Result])
