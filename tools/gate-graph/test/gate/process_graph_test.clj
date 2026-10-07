(ns gate.process-graph-test
  "Command projection controls cover false passes, clock binding, declared dependencies and actual execution."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.clock :as clock]
            [gate.coordinator :as coordinator]
            [gate.decimal :as d]
            [gate.graph :as graph]
            [gate.plan :as plan]
            [gate.process :as process]
            [gate.process-graph :as projection]
            [gate.process-test :as process-test]
            [gate.trace-test :refer [with-directory]]
            [gate.verdict :as verdict]
            [malli.core :as m]))

(def digest (canonical/sha256 "synthetic-process-source"))
(def work-digest (canonical/sha256 "independent-check-inventory"))
(def coverage {:unit :checks :expected work-digest :observed work-digest :count 1})
(def options {:run "example" :key "chain/example" :label "Example commands" :source-digest digest})
(def validation {:source-before digest :source-after digest :inputs-before digest :inputs-after digest
                 :coverage-error? false :publication-error? false})
(def gate {:id "command" :label "Example command" :command ["/usr/bin/printf" "checked"]
           :cwd "." :inputs [] :outputs [] :environment [] :toolchains ["fixture"]
           :dependencies [] :cache :always :network :allowed
           :coverage {:unit :checks :expected work-digest :minimum 1}})

(defn observation
  "Synthetic supervision has an actual process identity and an independently known complete log."
  []
  {:schema/version 1 :status :exited :exit 0 :pid "123" :elapsed-ns "10" :containment :none
   :cleanup-required? false :observed-processes 1 :observed-processes-stopped? true
   :log {:file "command.log" :bytes 7 :digest (canonical/sha256 "checked") :truncated? false}})

(defn scenario
  "One command's declaration, separately supplied work witness, anchored observation and dispatch."
  [observed supplied]
  (let [work (verdict/process-result gate observed supplied)]
    {:gates [gate] :roots [(:id gate)]
     :captures [{:schema/version 1 :run (:run options) :gate (:id gate) :label (:label gate)
                 :source-digest digest :command (:command gate) :cwd (:cwd gate) :coverage supplied :validation validation
                 :process {:schema/version 1 :clock "clock" :offset-ns "5" :observation observed}}]
     :batch {:schema/version 1 :clock "clock" :duration-ns "30" :schedule (plan/schedule [gate] [(:id gate)])
             :status (if (= :passed (:outcome work)) :passed :failed)
             :dispatches [{:gate (:id gate) :outcome (:outcome work) :at-ns "20"
                           :adapter-interval {:start-ns "2" :end-ns "20"} :work work :blocked-by []}]}}))

(defn project
  "Apply the actual portable projector to independent fixture data."
  [fixture]
  (projection/project options (:gates fixture) (:roots fixture) (:batch fixture) (:captures fixture)))

(defn refusal
  "Expose the closed refusal while unrelated exception classes still fail the test."
  [fixture]
  (try (project fixture) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest command-supervision-has-an-exact-span-without-invented-test-or-cpu-measurements
  (let [fixture (scenario (observation) coverage) value (project fixture)
        command (first (filter #(= :gate (:kind %)) (:nodes value)))
        capture (first (:captures fixture))]
    (is (empty? (graph/findings value)))
    (is (get-in value [:run :complete?]))
    (is (= :execution (:record command)))
    (is (= {:start-ns "5" :end-ns "15"} (:interval command)))
    (is (= :passed (:outcome command)))
    (is (= 3 (count (:nodes value))))
    (is (empty? (:measurements value)))
    (is (= (canonical/sha256 (canonical/encode capture 67108864))
           (:digest (first (filter #(= (:source command) (:id %)) (:sources value))))))
    (is (= value (admission/decode (canonical/encode value 65536) :graph admission/default-limits)))
    (is (= capture (admission/decode (canonical/encode capture 65536) :process-capture admission/default-limits)))))

(deftest wrong-clock-command-source-coverage-and-interval-refuse-before-projection
  (let [fixture (scenario (observation) coverage)]
    (doseq [[path replacement expected]
            [[[:captures 0 :run] "other" :process-capture-binding]
             [[:captures 0 :gate] "other" :process-capture-binding]
             [[:captures 0 :label] "other" :process-capture-binding]
             [[:captures 0 :source-digest] work-digest :process-capture-binding]
             [[:captures 0 :command] ["/bin/true"] :process-capture-binding]
             [[:captures 0 :cwd] "other" :process-capture-binding]
             [[:captures 0 :process :clock] "other" :process-capture-binding]
             [[:captures 0 :process :offset-ns] "1" :process-capture-interval]
             [[:captures 0 :process :observation :elapsed-ns] "16" :process-capture-interval]
             [[:captures 0 :coverage :count] 0 :process-capture-verdict]
             [[:captures 0 :process :observation :exit] 7 :process-capture-verdict]
             [[:captures 0 :process :observation :pid] nil :process-capture-verdict]]]
      (let [changed (assoc-in fixture path replacement)]
        (is (m/validate projection/Captures (:captures changed)))
        (is (= expected (:code (refusal changed))) (str path))))
    (is (= :process-capture-binding (:code (refusal (update fixture :captures conj (first (:captures fixture)))))))
    (is (false? (get-in (project (assoc fixture :captures [])) [:run :complete?])))))

(deftest failures-cancellation-limits-and-failed-launch-remain-distinct-evidence
  (doseq [[obs supplied record outcome complete?]
          [[(assoc (observation) :exit 7) coverage :execution :failed true]
           [(observation) nil :execution :error true]
           [(assoc (observation) :status :cancelled :exit 137) nil :execution :cancelled true]
           [(assoc (observation) :status :timed-out :exit 137) nil :execution :error true]
           [(assoc-in (assoc (observation) :status :output-limit) [:log :truncated?] true)
            nil :execution :error false]
           [(assoc (observation) :observed-processes-stopped? false) nil :execution :error false]
           [(assoc (observation) :status :launch-failed :pid nil :exit nil :observed-processes 0)
            nil :decision :refused true]
           [(assoc (observation) :status :cancelled :pid nil :exit nil :observed-processes 0 :log nil)
            nil :decision :cancelled true]]]
    (let [value (project (scenario obs supplied)) node (first (filter #(= :gate (:kind %)) (:nodes value)))]
      (is (= record (:record node)))
      (is (= outcome (:outcome node)))
      (is (= complete? (get-in value [:run :complete?])))
      (is (= (= :execution record) (contains? node :interval))))))

(deftest exact-large-offsets-preserve-process-duration
  (is (:pass?
       (tc/quick-check
        80
        (prop/for-all [offset (gen/choose 1 100000) duration (gen/choose 1 100000)]
                      (let [start (+ 9007199254740993 offset) end (+ start duration)
                            fixture (-> (scenario (assoc (observation) :elapsed-ns (str duration)) coverage)
                                        (assoc-in [:captures 0 :process :offset-ns] (str start))
                                        (assoc-in [:batch :dispatches 0 :adapter-interval] {:start-ns (str (dec start)) :end-ns (str (inc end))})
                                        (assoc-in [:batch :dispatches 0 :at-ns] (str (inc end)))
                                        (assoc-in [:batch :duration-ns] (str (+ end 2))))
                            value (project fixture) node (first (filter #(= :gate (:kind %)) (:nodes value)))]
                        (= {:start-ns (str start) :end-ns (str end)} (:interval node))))
        :seed 20261007))))

(deftest real-coordinator-process-and-dependency-clock-domains-join
  (with-directory
    (fn [directory]
      (let [context (clock/start!) captures (atom [])
            second-gate (assoc gate :id "second" :dependencies [{:gate (:id gate) :relation :requires}])
            gates [gate second-gate]
            backend (fn [declaration cancellation]
                      (let [before (clock/now context)
                            result (process/run-clocked!
                                    (assoc (process-test/request directory (:command declaration))
                                           :log (str (:id declaration) ".log")) cancellation context)
                            after (clock/now context)
                            supplied (when (= "checked" (slurp (str directory "/" (:id declaration) ".log"))) coverage)]
                        (is (d/before-or-equal? before (:offset-ns result)))
                        (is (d/before-or-equal? (d/add (:offset-ns result) (get-in result [:observation :elapsed-ns])) after))
                        (is (= result (admission/decode (canonical/encode result 65536) :clocked-process admission/default-limits)))
                        (swap! captures conj {:schema/version 1 :run (:run options) :gate (:id declaration)
                                              :label (:label declaration) :source-digest digest
                                              :command (:command declaration) :cwd (:cwd declaration)
                                              :process result :coverage supplied :validation validation})
                        (process/work-result declaration (:observation result) supplied)))
            observed (coordinator/run-clocked! gates ["second"] {:jobs 2 :claims {}} backend (atom false) context)
            value (projection/project options gates ["second"] observed @captures)
            edge (first (:edges value))]
        (is (= :passed (:status observed)))
        (is (= :observed (:evidence edge)))
        (is (= [:finish :start] [(get-in edge [:from :phase]) (get-in edge [:to :phase])])))
      nil)))
