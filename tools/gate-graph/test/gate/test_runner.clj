(ns gate.test-runner
  "Instrumented non-vacuous test entrypoint, including private function-contract coverage."
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [gate.admission-test]
            [gate.api-source-test]
            [gate.api-test]
            [gate.archive-io-test]
            [gate.archive-run-test]
            [gate.archive-test]
            [gate.attempt-test]
            [gate.batch-graph-test]
            [gate.clojure-test-test]
            [gate.contained-test]
            [gate.container]
            [gate.coordinator-test]
            [gate.decimal-test]
            [gate.diagnostic :as diagnostic]
            [gate.diagnostic-test]
            [gate.diff-test]
            [gate.graph-test]
            [gate.inputs-test]
            [gate.interval-test]
            [gate.measure-test]
            [gate.ownership-test]
            [gate.process-batch-test]
            [gate.process-cli-test]
            [gate.process-graph-test]
            [gate.process-test]
            [gate.publish-test]
            [gate.query-test]
            [gate.report-io-test]
            [gate.report-publish :as html]
            [gate.report-publish-test]
            [gate.report-test]
            [gate.repository-test]
            [gate.run-test]
            [gate.runtime-test]
            [gate.snapshot-test]
            [gate.store-test]
            [gate.test-artifact :as artifact]
            [gate.test-artifact-test]
            [gate.test-batch :as batch]
            [gate.test-batch-test]
            [gate.trace-cli]
            [gate.trace-test]
            [gate.view.model-test]
            [gate.view.opportunity-test]
            [gate.viewer-asset :as viewer]
            [gate.viewer-asset-test]
            [malli.core :as m]))

(def source-namespaces '[gate.api-source gate.api-source-contract gate.api gate.api-contract gate.view.contract gate.view.model gate.view.opportunity gate.archive gate.archive-contract gate.archive-io gate.archive-run gate.repository-identity gate.repository gate.repository-git gate.repository-contract gate.viewer-asset gate.viewer-build gate.report-publish gate.contract gate.inspection-contract gate.schema gate.decimal gate.graph gate.interval gate.canonical gate.query gate.measure gate.diff gate.admission
                         gate.trace-contract gate.trace-io gate.trace-import gate.report gate.report-io gate.trace-cli
                         gate.run-contract gate.plan gate.cache gate.store gate.diagnostic gate.coordinator gate.inputs gate.attempt gate.process gate.process-batch gate.process-graph gate.snapshot gate.container gate.publish gate.contained gate.runtime gate.ownership gate.clojure-test gate.test-graph gate.test-artifact gate.clock gate.batch-graph gate.test-batch gate.verdict])

(def test-namespaces
  '[gate.api-source-test gate.api-test gate.view.model-test gate.view.opportunity-test gate.archive-test gate.archive-io-test gate.archive-run-test gate.repository-test gate.viewer-asset-test gate.report-publish-test gate.admission-test gate.decimal-test gate.graph-test gate.interval-test gate.query-test
    gate.measure-test gate.diff-test gate.trace-test gate.report-test gate.report-io-test gate.run-test gate.store-test
    gate.diagnostic-test gate.coordinator-test gate.inputs-test gate.attempt-test gate.process-test gate.process-batch-test gate.process-cli-test gate.process-graph-test
    gate.snapshot-test gate.publish-test gate.contained-test gate.runtime-test gate.ownership-test
    gate.clojure-test-test gate.test-artifact-test gate.batch-graph-test gate.test-batch-test])

(defn cold-admission!
  "Run the deep-input regression before ordinary examples can warm up the instrumented parser."
  []
  (let [{:keys [pass fail error] test-count :test}
        (binding [test/*report-counters* (ref test/*initial-report-counters*)]
          (test/test-var #'gate.admission-test/nested-vectors-spend-limits-before-growing)
          @test/*report-counters*)]
    (when-not (and (= 1 test-count) (pos? pass) (zero? fail) (zero? error))
      (throw (ex-info "Cold admission regression failed" {:pass pass :fail fail :error error})))
    (println "Cold admission regression:" test-count "test," pass "assertions")))
(m/=> cold-admission! [:=> [:cat] :nil])

(defn prepare!
  "Verify all loaded source function contracts and docstrings, then install compact diagnostics."
  []
  (let [schemas (m/function-schemas)
        missing (for [nsym source-namespaces
                      [var-name v] (ns-interns nsym)
                      :when (and (bound? v) (fn? @v) (nil? (get-in schemas [nsym var-name])))]
                  (symbol (str nsym) (str var-name)))]
    (when (seq missing)
      (throw (ex-info "Missing function contracts" {:missing (vec missing)})))
    (let [undocumented (for [nsym source-namespaces [var-name v] (ns-interns nsym)
                             :when (and (bound? v) (fn? @v) (str/blank? (:doc (meta v))))]
                         (symbol (str nsym) (str var-name)))]
      (when (seq undocumented) (throw (ex-info "Missing function docstrings" {:missing (vec undocumented)}))))
    (diagnostic/install!)))
(m/=> prepare! [:=> [:cat] :nil])

(defn suite!
  "Run the cold parser regression first, then the full roster; the observer retains both invocations."
  []
  (cold-admission!)
  (apply test/run-tests test-namespaces)
  nil)
(m/=> suite! [:=> [:cat] :nil])

(defn -main
  "Observe the full suite and save source-bound EDN with matching standalone HTML.
   Optional argument: fresh report directory. Verify the packaged viewer before tests.
   Default reports stay in ignored .gate-reports. A failed/refused suite or publication fails the command."
  [& [output]]
  (prepare!)
  (let [asset (viewer/load!)
        run (str "module-" (java.util.UUID/randomUUID))
        output (or output (str ".gate-reports/" run))
        gate (batch/declaration "gate/module-tests" "Execution graph module tests" test-namespaces [])
        result (try
                 (batch/run! {:run run :key "chain/module-tests" :label "Execution graph module test chain"
                              :coordinator {:jobs 1 :claims {}} :max-tests 10000 :max-assertions 1000000}
                             [gate] [(:id gate)] {(:id gate) {:namespaces test-namespaces :runner suite!}}
                             (artifact/classpath) output (atom false))
                 (finally (shutdown-agents)))]
    (html/publish! output (:graph result) asset)
    (System/exit (if (= :passed (:status result)) 0 1))))
(m/=> -main [:=> [:cat [:? :string]] :nil])
