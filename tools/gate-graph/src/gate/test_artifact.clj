(ns gate.test-artifact
  "Source-bound, create-only per-test EDN reports for trusted JVM test commands."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.clock :as clock]
            [gate.clojure-test :as observer]
            [gate.contract :as c]
            [gate.inputs :as inputs]
            [gate.run-contract :as r]
            [gate.runtime :as runtime]
            [gate.test-graph :as projection]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(def Options
  [:map {:closed true} [:run c/Id] [:gate c/Id] [:label c/Label]
   [:max-tests [:int {:min 1 :max 10000}]] [:max-assertions [:int {:min 1 :max 1000000}]]
   [:expected-source {:optional true} c/Digest]])
(def Failure [:map {:closed true} [:code [:enum :report-path :report-io :report-roundtrip]]])

(defn classpath
  "Return the actual ordered JVM classpath; callers add other inputs their tests read.
   Report directories must lie outside every directory supplied as a source root."
  []
  (vec (str/split (System/getProperty "java.class.path") (re-pattern java.io.File/pathSeparator))))
(m/=> classpath [:=> [:cat] runtime/Paths])

(defn source-digest
  "Hash actual ordered source/dependency bytes using the controller traversal and byte limits.
   Loaded code must remain immutable. Before/after equality does not exclude edit/revert cycles."
  [roots]
  (runtime/content-digest roots runtime/controller-limits))
(m/=> source-digest [:=> [:cat runtime/Paths] c/Digest])

(defn output-directory!
  "Create one fresh report directory outside supplied directory inputs, resolving parent aliases.
   Existing runs refuse. This is trusted local filesystem handling, not hostile-writer isolation."
  [roots output]
  (let [requested (.normalize (.toAbsolutePath (Path/of output (make-array String 0))))
        parent (.getParent requested)]
    (when-not parent (throw (ex-info "Report path refused" {:code :report-path})))
    (Files/createDirectories parent (make-array FileAttribute 0))
    (let [path (.resolve (.toRealPath parent (make-array LinkOption 0)) (.getFileName requested))]
      (doseq [entry roots
              :let [source (Path/of entry (make-array String 0))]
              :when (Files/isDirectory source (make-array LinkOption 0))]
        (when (.startsWith path (.toRealPath source (make-array LinkOption 0)))
          (throw (ex-info "Report path refused" {:code :report-path}))))
      (Files/createDirectory path (make-array FileAttribute 0))
      (str path))))
(m/=> output-directory! [:=> [:cat runtime/Paths inputs/Root] inputs/Root])

(defn write-record!
  "Check bounded canonical admission before create-only UTF-8 publication; never replace evidence."
  [directory filename value target]
  (let [text (canonical/encode value 67108864)]
    (when-not (= value (admission/decode text target admission/default-limits))
      (throw (ex-info "Report roundtrip refused" {:code :report-roundtrip})))
    (Files/writeString (.resolve (Path/of directory (make-array String 0)) ^String filename)
                       (str text "\n") StandardCharsets/UTF_8
                       (into-array OpenOption [StandardOpenOption/WRITE StandardOpenOption/CREATE_NEW]))
    nil))
(m/=> write-record! [:=> [:cat inputs/Root [:enum "tests.edn" "graph.edn" "anchor.edn" "batch.edn" "definitions.edn"]
                          [:or r/TestObservation c/Graph r/ClockedTests r/Batch r/Gates]
                          [:enum :test-observation :graph :clocked-tests :coordinator-batch :gate-definitions]] :nil])

(defn- observer-options
  "Keep producer source policy out of the observer's closed acquisition options."
  [options source]
  (assoc (select-keys options [:run :gate :label :max-tests :max-assertions]) :source-digest source))
(m/=> observer-options [:=> [:cat Options c/Digest] observer/Options])

(defn- settled
  "Reconcile actual before/after bytes and an optional run-wide expected source identity."
  [options roots before observation]
  (let [stable? (= before (source-digest roots))
        expected? (or (nil? (:expected-source options)) (= before (:expected-source options)))]
    (if (and stable? expected?) observation
        (merge observation
               (projection/judge (:inventory observation) (:executions observation) (:unattributed observation)
                                 (conj (:problems observation) :source-changed))))))
(m/=> settled [:=> [:cat Options runtime/Paths c/Digest r/TestObservation] r/TestObservation])

(defn- publish-tests!
  "Publish the source-judged observation and its validated local-clock graph."
  [directory observation]
  (write-record! directory "tests.edn" observation :test-observation)
  (write-record! directory "graph.edn" (projection/project observation) :graph)
  (locking *out* (prn {:status (:status observation) :report (str directory "/graph.edn")}))
  nil)
(m/=> publish-tests! [:=> [:cat inputs/Root r/TestObservation] :nil])

(defn run!
  "Run trusted tests and publish source-bound tests.edn plus their canonical drillable graph.edn.
   Source roots must include the executing classpath and all other inputs read by the tests.
   Changed source retains captured work while refusing coverage. A native I/O failure fails the
   command with compact data; publication is create-only but not a two-file transaction. An incomplete
   directory must not be promoted to a complete report. The runner must join all asynchronous work.
   HTML requires a matching viewer build; this function publishes EDN only."
  [options namespaces roots output runner]
  (try
    (let [directory (output-directory! roots output)
          before (source-digest roots)
          observation (observer/run-with! (observer-options options before) namespaces runner)
          observation (settled options roots before observation)]
      (publish-tests! directory observation)
      observation)
    (catch java.io.IOException _ (throw (ex-info "Report I/O failed" {:code :report-io})))))
(m/=> run! [:=> [:cat Options observer/Namespaces runtime/Paths inputs/Root observer/Runner] r/TestObservation])

(defn run-in!
  "Publish tests plus their exact one-JVM anchor for joining a coordinator run.
   Optional :expected-source binds every gate to the same run-wide source bytes. Source changes
   retain observations without coverage. Per-gate I/O failure throws; the coordinator must preserve
   that failure and report incomplete acquisition rather than invent missing execution spans."
  [options namespaces roots output runner context]
  (try
    (let [directory (output-directory! roots output) before (source-digest roots)
          captured (observer/run-in! (observer-options options before) namespaces runner context)
          captured (update captured :observation #(settled options roots before %))]
      (publish-tests! directory (:observation captured))
      (write-record! directory "anchor.edn" captured :clocked-tests)
      captured)
    (catch java.io.IOException _ (throw (ex-info "Report I/O failed" {:code :report-io})))))
(m/=> run-in! [:=> [:cat Options observer/Namespaces runtime/Paths inputs/Root observer/Runner clock/Context] r/ClockedTests])
