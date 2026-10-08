(ns gate.mutation-runner
  "Fresh-process attributed admission tests; used only by the manual mutation campaign."
  (:require [clojure.test :as test]
            [gate.admission-test]
            [gate.api-test]
            [gate.archive-io-test]
            [gate.archive-run-test]
            [gate.archive-test]
            [gate.attempt-test]
            [gate.batch-graph-test]
            [gate.clojure-test-test]
            [gate.contained-test]
            [gate.container-test :as container-test]
            [gate.coordinator-test]
            [gate.graph-test]
            [gate.inputs-test]
            [gate.measure-test]
            [gate.ownership-test]
            [gate.process-batch-test]
            [gate.process-cli-test]
            [gate.process-graph-test]
            [gate.process-test]
            [gate.publish-test]
            [gate.report-publish-test]
            [gate.repository-test]
            [gate.run-test]
            [gate.runtime-test]
            [gate.snapshot-test]
            [gate.test-artifact-test]
            [gate.test-batch-test]
            [gate.view.model-test]
            [gate.view.opportunity-test]
            [gate.viewer-asset-test]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def Counters
  [:map {:closed true} [:test nat-int?] [:pass nat-int?] [:fail nat-int?] [:error nat-int?]])
(def Result [:map {:closed true} [:target Counters] [:control Counters]])
(def TestName [:re #"^[a-z][a-z0-9-]{0,159}$"])
(def TestNamespace [:enum "gate.api-test" "gate.view.model-test" "gate.view.opportunity-test" "gate.measure-test" "gate.archive-test" "gate.archive-io-test" "gate.archive-run-test" "gate.repository-test" "gate.viewer-asset-test" "gate.report-publish-test" "gate.admission-test" "gate.coordinator-test" "gate.inputs-test" "gate.attempt-test" "gate.process-test" "gate.process-batch-test" "gate.process-cli-test" "gate.process-graph-test" "gate.container-test" "gate.publish-test" "gate.run-test" "gate.contained-test" "gate.runtime-test" "gate.ownership-test" "gate.clojure-test-test" "gate.test-artifact-test" "gate.batch-graph-test" "gate.test-batch-test" "gate.graph-test" "gate.snapshot-test"])

(defn run-selected
  "Require one actual test var, execute it, and print counters independently of its neighbor."
  [namespace-name role test-name]
  (let [target (ns-resolve (symbol namespace-name) (symbol test-name))]
    (when-not (:test (meta target))
      (throw (ex-info "Missing selected test" {:test-name test-name})))
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (test/test-vars [target])
      (let [{:keys [pass fail error] test-count :test} @test/*report-counters*]
        (println (str "RESULT " role " tests=" test-count " assertions=" (+ pass fail error)
                      " failures=" fail " errors=" error))
        @test/*report-counters*))))
(m/=> run-selected [:=> [:cat TestNamespace [:enum "target" "control"] TestName] Counters])

(defn -main
  "Instrument the same production vars as the normal suite, then judge target and control."
  [target control result-path & [namespace-name]]
  (mi/instrument!)
  (let [namespace-name (or namespace-name "gate.admission-test")
        execute #(hash-map :target (run-selected namespace-name "target" target) :control (run-selected namespace-name "control" control))
        result (if (= namespace-name "gate.container-test")
                 (container-test/with-runtime (System/getenv "GATE_CONTAINER_SCRATCH") (System/getenv "GATE_CONTAINER_IMAGE") execute)
                 (execute))]
    (when-not (m/validate Result result)
      (throw (ex-info "Invalid mutation test counters" {:code :invalid-test-counters})))
    (spit result-path (pr-str result)))
  (shutdown-agents))
(m/=> -main [:=> [:cat TestName TestName [:string {:min 1 :max 4096}] [:? TestNamespace]] :nil])
