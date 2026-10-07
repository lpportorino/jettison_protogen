(ns gate.coordinator-dogfood
  "Run this public module and the capture suite through the actual shared coordinator.
   This is a scoped acceptance backend, not the production process/isolation adapter."
  (:require [clojure.string :as str]
            [gate.admission :as admission]
            [gate.attempt :as attempt]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.coordinator :as coordinator]
            [gate.diagnostic :as diagnostic]
            [gate.inputs :as inputs]
            [gate.process :as process]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent CountDownLatch]))

(def PathText [:string {:min 1 :max 4096}])

(defn- suite-gate
  "Declare one whole-suite work unit; do not represent suite evidence as per-test coverage."
  [root id command]
  {:id id :label id :command (into [(str root "/tools/gate-trace/bin/trace-run") id "--kind" "gate" "--"] command)
   :cwd "."
   :inputs (into [{:id "capture" :kind :tree :path "tools/gate-trace" :required? true
                   :exclude ["**/__pycache__"]}
                  {:id "adapter" :kind :file :path "tools/gate-graph/test/gate/coordinator_dogfood.clj" :required? true}]
                 (when (= id "graph-tests")
                   [{:id "source" :kind :tree :path "tools/gate-graph/src" :required? true}
                    {:id "tests" :kind :tree :path "tools/gate-graph/test" :required? true}
                    {:id "dependencies" :kind :file :path "tools/gate-graph/deps.edn" :required? true}]))
   :outputs [] :environment [] :toolchains ["image"]
   :dependencies [] :cache :always :network :allowed
   :coverage {:expected (canonical/sha256 id) :minimum 1}})
(m/=> suite-gate [:=> [:cat PathText [:enum "graph-tests" "capture-tests"] [:vector {:min 1 :max 256} r/Text]] r/Gate])

(defn- witnessed?
  "Require each existing nonvacuous runner's positive summary as well as its process exit.
   The digest identifies one suite work unit; individual test IDs remain an adapter gap."
  [id text]
  (boolean
   (case id
     "graph-tests" (and (re-find #"Ran [1-9][0-9]* tests containing [1-9][0-9]* assertions\." text)
                        (str/includes? text "0 failures, 0 errors."))
     "capture-tests" (when-let [[_ run selected] (re-find #"gate-trace tests: ([1-9][0-9]*) of ([1-9][0-9]*) run, 0 failed, 0 errored, 0 skipped" text)]
                       (= run selected)))))
(m/=> witnessed? [:=> [:cat [:enum "graph-tests" "capture-tests"] [:string {:max 4194304}]] :boolean])

(defn- execute!
  "Use the shared bounded process backend, then require an independent whole-suite witness.
   This observation-only profile explicitly passes its current environment and claims no isolation."
  [root output gate cancellation]
  (let [observation (process/run!
                     {:directory root :cwd (:cwd gate) :command (:command gate)
                      :environment (inputs/system-environment (vec (keys (System/getenv))))
                      :stdin nil :log-directory output :log (str (:id gate) ".log")
                      :limits process/default-limits} cancellation)
        text (canonical/encode observation 65536)
        coverage (when (and (= :exited (:status observation)) (= 0 (:exit observation))
                            (witnessed? (:id gate) (slurp (str output "/" (:id gate) ".log"))))
                   {:expected (get-in gate [:coverage :expected])
                    :observed (canonical/sha256 (:id gate)) :count 1})]
    (when-not (= observation (admission/decode text :process-observation admission/default-limits))
      (throw (ex-info "Process roundtrip failed" {:code :dogfood-process-roundtrip})))
    (spit (str output "/" (:id gate) ".process.edn") (str text "\n"))
    (process/work-result gate observation coverage)))
(m/=> execute! [:=> [:cat PathText PathText r/Gate coordinator/Cancellation] r/WorkResult])

(defn- observed-execute!
  "Exercise real source observation and attempt admission around the scoped process backend.
   Supplied image digest is the caller's runtime attestation. Inherited environment/network
   and a mutable source mount are explicitly insufficient for cache reuse. Persist only
   bounded, schema-admitted attempt records; incomplete source observation fails dogfooding."
  [root output image-digest gate cancellation]
  (let [observation (attempt/run!
                     {:directory root :cache-directory (str output "/cache")
                      :run (str (.getFileName (Path/of output (make-array String 0)))) :attempt (:id gate) :gate gate
                      :evidence {:environment {} :toolchains {"image" image-digest}}
                      :limits inputs/default-limits :dependencies []
                      :force? false :isolation-verified? false}
                     (partial execute! root output) (constantly []) cancellation)
        text (canonical/encode observation 1048576)]
    (when-not (= observation (admission/decode text :attempt-observation admission/default-limits))
      (throw (ex-info "Attempt roundtrip failed" {:code :dogfood-attempt-roundtrip})))
    (spit (str output "/" (:id gate) ".attempt.edn") (str text "\n"))
    (when-not (and (get-in observation [:before :complete?])
                   (get-in observation [:after :complete?])
                   (= :unproven-isolation (get-in observation [:admission :reason]))
                   (= :not-attempted (:publication observation))
                   (= :not-attempted (:lookup observation)))
      (throw (ex-info "Attempt observation incomplete" {:code :dogfood-attempt-incomplete})))
    (:work observation)))
(m/=> observed-execute! [:=> [:cat PathText PathText c/Digest r/Gate coordinator/Cancellation] r/WorkResult])

(defn -main
  "Arguments: repository root, fresh output directory and caller-attested image SHA-256 hex.
   Emit canonical coordinator and live attempt evidence.
   Existing TELEMETRY_* variables from trace-start are inherited by this scoped backend.
   This harness intentionally does no caching and attests only the two named suite units."
  [root output image-digest]
  (diagnostic/install!)
  (Files/createDirectory (Path/of output (make-array String 0)) (make-array FileAttribute 0))
  (let [gates [(suite-gate root "graph-tests" ["bash" "-c" "cd tools/gate-graph && clojure -M:test"])
               (suite-gate root "capture-tests" ["bash" "tools/gate-trace/test/run_tests.sh" "--suite-only"])]
        token (atom false)
        finished (CountDownLatch. 1)
        hook (Thread. ^Runnable #(do (reset! token true) (.await finished)))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (let [batch (coordinator/run! gates (mapv :id gates) {:jobs 2 :claims {}}
                                    (partial observed-execute! root output image-digest) token)]
        (spit (str output "/batch.edn") (str (canonical/encode batch 1048576) "\n"))
        (prn {:status (:status batch) :gates (count (:dispatches batch)) :output output})
        (when-not (= :passed (:status batch)) (throw (ex-info "Coordinator dogfood failed" {:code :dogfood-failed}))))
      (finally
        (.countDown finished)
        (try (.removeShutdownHook (Runtime/getRuntime) hook)
             (catch IllegalStateException _ nil)))))
  (shutdown-agents))
(m/=> -main [:=> [:cat PathText PathText c/Digest] :nil])
