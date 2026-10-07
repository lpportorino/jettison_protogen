(ns gate.process-cli-fixture
  "Fresh JVM subject for actual CLI signal and publication proofs."
  (:require [gate.canonical :as canonical]
            [gate.diagnostic :as diagnostic]
            [gate.inputs :as inputs]
            [gate.process :as process]
            [gate.process-batch :as batch]
            [gate.test-artifact :as artifact]
            [malli.core :as m]))

(defn -main
  "Execute a real native batch, optionally holding its witness beyond the shutdown budget."
  [root mode timeout]
  (diagnostic/install!)
  (let [command (if (= mode "process") ["/bin/sh" "work.sh"] ["/bin/true"])
        gate {:id "first" :label "First command" :command command :cwd "."
              :inputs [{:id "script" :kind :file :path "work.sh" :required? true}]
              :outputs [] :environment [] :toolchains ["fixture"] :dependencies [] :cache :always :network :allowed
              :coverage (batch/command-expectation command ".")}
        next-gate (assoc gate :id "second" :label "Second command"
                         :command ["/bin/sh" "-c" "touch second-ran"]
                         :coverage (batch/command-expectation ["/bin/sh" "-c" "touch second-ran"] ".")
                         :dependencies [{:gate "first" :relation :requires}])
        result (batch/run-cli!
                (Long/parseLong timeout)
                (fn [cancellation]
                  (let [witness (if (= mode "process") :command-invocation
                                    (fn [_ _ _]
                                      (spit (str root "/ready") "ready")
                                      (if (= mode "stuck") (Thread/sleep 60000)
                                          (do (while (not @cancellation) (Thread/sleep 5))
                                              (Thread/sleep 200)))
                                      nil))
                        adapter {:directory root :environment {} :toolchains {"fixture" (canonical/sha256 "signal-fixture")}
                                 :stdin nil :witness witness}]
                    (batch/run! {:run "signal-fixture" :key "chain/signal" :label "Signal fixture"
                                 :coordinator {:jobs 1 :claims {}} :input-limits inputs/default-limits
                                 :process-limits (assoc process/default-limits :timeout-ms 60000)}
                                [gate next-gate] ["second"] {"first" adapter "second" adapter}
                                (artifact/classpath) (str root "/report") cancellation))))]
    (spit (str root "/returned") (canonical/encode result 67108864))
    (shutdown-agents)))
(m/=> -main [:=> [:cat :string [:enum "process" "witness" "stuck"] :string] :nil])
