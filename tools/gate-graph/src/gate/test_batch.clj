(ns gate.test-batch
  "Execute native test gates through the shared coordinator and publish one joined run graph."
  (:refer-clojure :exclude [run!])
  (:require [gate.batch-graph :as projection]
            [gate.canonical :as canonical]
            [gate.clock :as clock]
            [gate.clojure-test :as observer]
            [gate.contract :as c]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.plan :as plan]
            [gate.run-contract :as r]
            [gate.runtime :as runtime]
            [gate.test-artifact :as artifact]
            [malli.core :as m])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(def Suite [:map {:closed true} [:namespaces observer/Namespaces] [:runner observer/Runner]])
(def Suites [:map-of {:min 1 :max 10000} c/Id Suite])
(def Options
  [:map {:closed true} [:run c/Id] [:key c/Id] [:label c/Label]
   [:coordinator r/CoordinatorOptions] [:max-tests [:int {:min 1 :max 10000}]]
   [:max-assertions [:int {:min 1 :max 1000000}]]])
(def Failure [:map {:closed true} [:code [:enum :test-suite-binding :unsupported-test-policy]] [:subject c/Id]])
(def CaptureState [:fn #(instance? clojure.lang.Atom %)])

(defn declaration
  "Declare an always-run trusted native test gate with an independently discovered inventory.
   Its command is the native-adapter descriptor plus inventory digest, not subprocess argv.
   Callables stay outside EDN; supply their actual classpath/read inputs to run! for byte identity.
   This native profile has no network/process isolation and never enables cache receipt reuse."
  [id label namespaces dependencies]
  (let [inventory (observer/inventory namespaces)
        expected (canonical/sha256 (canonical/encode inventory 67108864))]
    {:id id :label label :command ["clojure.test" expected] :cwd "."
     :inputs [] :outputs [] :environment [] :toolchains ["jvm-test-controller"]
     :dependencies dependencies :cache :always :network :allowed
     :coverage {:expected expected :minimum (count inventory)}}))
(m/=> declaration [:=> [:cat c/Id c/Label observer/Namespaces [:vector {:max 1024} r/Dependency]] r/Gate])

(defn- validate-suites!
  "Require exact callable enrollment and positive independent inventory coverage before any test runs."
  [gates suites options]
  (when-not (= (set (map :id gates)) (set (keys suites)))
    (throw (ex-info "Native test suite enrollment refused" {:code :test-suite-binding :subject (:id (first gates))})))
  (doseq [gate gates]
    (when (or (not= :always (:cache gate)) (not= :allowed (:network gate))
              (seq (:outputs gate)) (seq (:inputs gate)) (seq (:environment gate))
              (not= ["jvm-test-controller"] (:toolchains gate)) (not= "." (:cwd gate)))
      (throw (ex-info "Unsupported native test policy" {:code :unsupported-test-policy :subject (:id gate)})))
    (let [inventory (observer/inventory (:namespaces (get suites (:id gate))))
          expected (canonical/sha256 (canonical/encode inventory 67108864))]
      (when (or (not= ["clojure.test" expected] (:command gate))
                (not= expected (get-in gate [:coverage :expected]))
                (> (get-in gate [:coverage :minimum]) (count inventory))
                (> (count inventory) (:max-tests options)))
        (throw (ex-info "Native test inventory binding refused" {:code :test-suite-binding :subject (:id gate)})))))
  nil)
(m/=> validate-suites! [:=> [:cat r/Gates Suites Options] :nil])

(defn- execute!
  "Run one source-bound suite, retaining its exact clock anchor and independent work witness."
  [options suites source-roots directory expected context captures gate cancellation]
  (if @cancellation
    {:outcome :cancelled :coverage nil :reason :cancellation-requested}
    (let [suite (get suites (:id gate))
          capture (artifact/run-in! {:run (:run options) :gate (:id gate) :label (:label gate)
                                     :max-tests (:max-tests options) :max-assertions (:max-assertions options)
                                     :expected-source expected}
                                    (:namespaces suite) source-roots
                                    (str directory "/gates/" (canonical/sha256 (:id gate)))
                                    (:runner suite) context)
          _ (swap! captures conj capture)
          observation (:observation capture)]
      {:outcome (:status observation) :coverage (:coverage observation)
       :reason (case (:status observation)
                 :passed nil :failed :command-failed
                 :error (if (some #{:source-changed} (:problems observation)) :input-unstable :test-observation-error))})))
(m/=> execute! [:=> [:cat Options Suites runtime/Paths inputs/Root c/Digest clock/Context CaptureState
                     r/Gate coordinator/Cancellation] r/WorkResult])

(defn run!
  "Execute the selected native test DAG and publish definitions.edn, batch.edn and one graph.edn.
   Per-gate directories use hashed IDs and hold source-judged tests.edn, local graph.edn and anchor.edn.
   All captures use one unchanged same-JVM clock context. Actual tests sit under adapter boundaries;
   blocked/deselected/cancelled gates remain decisions and cannot acquire fabricated execution time.
   Complete graph acquisition and a passing Batch are both required for a passing report.

   The caller supplies loaded namespaces, synchronous callables and every classpath/read input root.
   Callable enrollment must cover the entire declared roster, including deselected gates. Every gate
   verifies run-wide expected source bytes before/after its work. This is an always-run native profile:
   no cache/isolation claim, arbitrary-code deadline or detached-thread cleanup is supplied. Cancellation
   stops pending dispatch and waits for running synchronous tests. Use isolated backends for untrusted
   or uncooperative work. Failed per-gate acquisition/publication remains an adapter error and incomplete
   graph; this API has no fault-safe recovery journal. Output files are create-only, not transactional."
  [options gates roots suites source-roots output cancellation]
  (plan/schedule gates roots)
  (validate-suites! gates suites options)
  (let [directory (artifact/output-directory! source-roots output)
        _ (Files/createDirectory (.resolve (Path/of directory (make-array String 0)) "gates") (make-array FileAttribute 0))
        expected (artifact/source-digest source-roots)
        context (clock/start!) captures (atom [])
        batch (coordinator/run-clocked! gates roots (:coordinator options)
                                        (partial execute! options suites source-roots directory expected context captures)
                                        cancellation context)
        captures (vec (sort-by #(get-in % [:observation :gate]) @captures))
        graph (projection/project (assoc (select-keys options [:run :key :label]) :source-digest expected)
                                  gates roots batch captures)
        status (cond (= :cancelled (:status batch)) :cancelled
                     (and (= :passed (:status batch)) (get-in graph [:run :complete?])) :passed
                     :else :failed)]
    (artifact/write-record! directory "definitions.edn" gates :gate-definitions)
    (artifact/write-record! directory "batch.edn" batch :coordinator-batch)
    (artifact/write-record! directory "graph.edn" graph :graph)
    (locking *out* (prn {:status status :gates (count (:dispatches batch)) :report (str directory "/graph.edn")}))
    {:schema/version 1 :status status :batch batch :graph graph :captures captures}))
(m/=> run! [:=> [:cat Options r/Gates projection/Roots Suites runtime/Paths inputs/Root coordinator/Cancellation] r/TestBatchReport])
