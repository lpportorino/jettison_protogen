(ns gate.batch-graph-test
  "Independent clock, reconciliation, containment, decision and dependency projection controls."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.admission :as admission]
            [gate.batch-graph :as projection]
            [gate.canonical :as canonical]
            [gate.clock :as clock]
            [gate.contract :as c]
            [gate.decimal :as d]
            [gate.graph :as graph]
            [gate.plan :as plan]
            [gate.query :as query]
            [gate.run-contract :as r]
            [gate.test-graph :as tests]
            [malli.core :as m]))

(def digest (canonical/sha256 "synthetic-batch-source"))
(def inventory [{:key "test/example" :label "Synthetic test"}])
(def coverage-digest (canonical/sha256 (canonical/encode inventory 4096)))
(def options {:run "batch-example" :key "chain/example" :label "Synthetic chain" :source-digest digest})

(defn gate
  "Declare synthetic work independently from the observed task/adapter intervals."
  [id dependencies]
  {:id id :label id :command ["synthetic"] :cwd "." :inputs [] :outputs [] :environment []
   :toolchains ["synthetic"] :dependencies dependencies :cache :always :network :allowed
   :coverage {:unit :tests :expected coverage-digest :minimum 1}})

(defn capture
  "Build a closed synthetic local observation and its separate known exact anchor."
  [id offset counts]
  (let [entries [{:id "test-0" :key "test/example" :label "Synthetic test" :parent nil :attempt 1
                  :interval {:start-ns "2" :end-ns "4"} :counts counts :returned? true}]
        unattributed {:pass 0 :fail 0 :error 0}]
    {:schema/version 1 :clock "synthetic-clock" :offset-ns offset
     :observation (merge {:schema/version 1 :run (:run options) :gate id :label id :source-digest digest
                          :inventory inventory :executions entries :unattributed unattributed :duration-ns "8"}
                         (tests/judge inventory entries unattributed []))}))

(defn fixture
  "Two executed gates and one deselected invalidation input; no observed roster is used to declare work."
  []
  (let [gates [(gate "a" []) (gate "b" [{:gate "a" :relation :requires} {:gate "unused" :relation :invalidates}]) (gate "unused" [])]
        captures [(capture "a" "10" {:pass 2 :fail 0 :error 0}) (capture "b" "30" {:pass 2 :fail 0 :error 0})]
        schedule (plan/schedule gates ["b"])
        by-id {"a" {:gate "a" :outcome :passed :at-ns "20" :adapter-interval {:start-ns "5" :end-ns "20"}
                    :work {:outcome :passed :coverage (get-in captures [0 :observation :coverage]) :reason nil} :blocked-by []}
               "b" {:gate "b" :outcome :passed :at-ns "40" :adapter-interval {:start-ns "25" :end-ns "40"}
                    :work {:outcome :passed :coverage (get-in captures [1 :observation :coverage]) :reason nil} :blocked-by []}
               "unused" {:gate "unused" :outcome :deselected :at-ns "0" :adapter-interval nil :work nil :blocked-by []}}]
    {:gates gates :roots ["b"] :captures captures
     :batch {:schema/version 1 :clock "synthetic-clock" :duration-ns "45" :status :passed :schedule schedule
             :dispatches (mapv by-id (:order schedule))}}))

(defn project
  "Apply the real portable projector to one independently authored scenario."
  [scenario]
  (projection/project options (:gates scenario) (:roots scenario) (:batch scenario) (:captures scenario)))

(defn failure
  "Expose named structured refusals while leaving unrelated native failures visible."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest signed-monotonic-ticks-wrap-without-wall-clock-or-floating-point-alignment
  (is (= "9" (clock/difference (+ Long/MIN_VALUE 4) (- Long/MAX_VALUE 4))))
  (is (= "1" (clock/difference Long/MIN_VALUE Long/MAX_VALUE)))
  (is (= :invalid-clock-anchor (:code (failure #(clock/difference 1 2)))))
  (let [context (clock/start!)]
    (is (m/validate clock/Context context))
    (is (m/validate c/Natural (clock/now context)))
    (is (not (m/validate clock/Context (assoc context :owner (Object.)))))
    (is (not (m/validate canonical/Encodable context)))))

(deftest actual-tests-and-adapter-boundaries-have-separate-exact-intervals
  (let [scenario (fixture) value (project scenario) context (query/prepare value)
        nodes (into {} (map (juxt :id identity) (:nodes value)))
        suite (get nodes (projection/identifier "gate" "a" "a"))
        adapter (get nodes (:parent suite))
        child (first (:rows (query/page context {:select {:op :children :anchor (:id suite)}
                                                 :budget {:visits 100 :rows 100 :bytes 65536}})))]
    (is (empty? (graph/findings value)))
    (is (= {:start-ns "10" :end-ns "18"} (:interval suite)))
    (is (= {:start-ns "5" :end-ns "20"} (:interval adapter)))
    (is (= :boundary (:kind adapter)))
    (is (= {:start-ns "12" :end-ns "14"} (:interval child)))
    (is (= 10 (count (:measurements value))))
    (is (= 10 (count (set (map :id (:measurements value))))))
    (is (= #{:requires :invalidates} (set (map :kind (:edges value)))))
    (is (= #{:observed} (set (map :evidence (filter #(= :requires (:kind %)) (:edges value))))))
    (is (= #{:declared} (set (map :evidence (filter #(= :invalidates (:kind %)) (:edges value))))))
    (is (= value (admission/decode (canonical/encode value 65536) :graph admission/default-limits)))
    (is (= (first (:captures scenario)) (admission/decode (canonical/encode (first (:captures scenario)) 65536)
                                                          :clocked-tests admission/default-limits)))))

(deftest projection-reconciles-clock-coverage-roster-status-and-prerequisite-order
  (let [scenario (fixture)]
    (doseq [[changed expected]
            [[(update-in scenario [:batch :dispatches] #(vec (butlast %))) :batch-roster]
             [(assoc-in scenario [:batch :status] :failed) :batch-status]
             [(assoc-in scenario [:batch :dispatches 2 :adapter-interval :start-ns] "10") :batch-dispatch]
             [(assoc-in scenario [:batch :dispatches 0 :work :reason] :cache-hit) :batch-dispatch]
             [(assoc-in scenario [:captures 0 :clock] "different-clock") :capture-binding]
             [(assoc-in scenario [:captures 0 :offset-ns] "100") :capture-interval]
             [(assoc-in scenario [:captures 0 :observation :run] "different-run") :capture-binding]
             [(assoc-in scenario [:captures 0 :observation :label] "different-label") :capture-binding]
             [(assoc-in scenario [:captures 0 :observation :coverage :count] 0) :capture-coverage]]]
      (is (not= scenario changed))
      (is (m/validate r/Batch (:batch changed)))
      (is (m/validate projection/Captures (:captures changed)))
      (is (= expected (:code (failure #(project changed)))) (str expected)))))

(deftest unobserved-passing-adapters-never-become-fabricated-passing-executions
  (let [scenario (fixture) value (project (assoc scenario :captures [(first (:captures scenario))]))
        missing (first (filter #(= "b" (:key %)) (:nodes value)))]
    (is (false? (get-in value [:run :complete?])))
    (is (= :decision (:record missing)))
    (is (= :refused (:outcome missing)))
    (is (= :missing-evidence (get-in missing [:reason :code])))
    (is (nil? (:interval missing)))))

(deftest undispatched-cancellation-retains-decisions-and-no-test-time
  (let [scenario (fixture)
        scenario (-> scenario (assoc :captures []) (assoc-in [:batch :status] :cancelled)
                     (update-in [:batch :dispatches]
                                #(mapv (fn [dispatch]
                                         (if (= :deselected (:outcome dispatch)) dispatch
                                             (assoc dispatch :outcome :cancelled :at-ns "5" :work nil :adapter-interval nil))) %)))
        value (project scenario) gates (filter #(= :gate (:kind %)) (:nodes value))]
    (is (get-in value [:run :complete?]))
    (is (= 1 (count (filter #(= :execution (:record %)) (:nodes value)))))
    (is (= [:cancelled :cancelled :deselected] (mapv :outcome gates)))
    (is (every? #(nil? (:interval %)) gates))
    (is (empty? (:measurements value)))))

(deftest graph-expansion-budget-refuses-before-copying-valid-many-gate-captures
  (let [ids (mapv #(str "gate/" %) (range 1365))
        gates (mapv #(gate % []) ids)
        captures (mapv #(capture % "10" {:pass 1 :fail 0 :error 0}) ids)
        schedule (plan/schedule gates ids)
        by-id (into {} (for [record captures :let [id (get-in record [:observation :gate])]]
                         [id {:gate id :outcome :passed :at-ns "20" :adapter-interval {:start-ns "5" :end-ns "20"}
                              :work {:outcome :passed :coverage (get-in record [:observation :coverage]) :reason nil} :blocked-by []}]))
        scenario {:gates gates :roots ids :captures captures
                  :batch {:schema/version 1 :clock "synthetic-clock" :duration-ns "45" :status :passed
                          :schedule schedule :dispatches (mapv by-id (:order schedule))}}]
    (is (m/validate r/Batch (:batch scenario)))
    (is (= :batch-evidence-budget (:code (failure #(project scenario)))))))

(deftest generated-exact-offsets-preserve-local-durations-and-measurement-ownership
  (let [result (tc/quick-check
                100
                (prop/for-all [offset (gen/choose 5 1000000)]
                              (let [scenario (fixture)
                                    scenario (-> scenario
                                                 (assoc :gates [(first (:gates scenario))] :roots ["a"] :captures [(capture "a" (str offset) {:pass 2 :fail 0 :error 0})])
                                                 (assoc :batch {:schema/version 1 :clock "synthetic-clock" :status :passed
                                                                :duration-ns (str (+ offset 20))
                                                                :schedule {:order ["a"] :selected ["a"] :deselected []}
                                                                :dispatches [{:gate "a" :outcome :passed :at-ns (str (+ offset 10))
                                                                              :adapter-interval {:start-ns "0" :end-ns (str (+ offset 10))}
                                                                              :work {:outcome :passed :coverage (get-in scenario [:captures 0 :observation :coverage]) :reason nil}
                                                                              :blocked-by []}]}))
                                    value (project scenario)
                                    suite (first (filter #(= :gate (:kind %)) (:nodes value)))
                                    child (first (filter #(= :test (:kind %)) (:nodes value)))]
                                (and (= "8" (d/subtract (get-in suite [:interval :end-ns]) (get-in suite [:interval :start-ns])))
                                     (= (str (+ offset 2)) (get-in child [:interval :start-ns]))
                                     (every? #(= (:id child) (:node %)) (:measurements value))
                                     (empty? (graph/findings value))))) :seed 20261009)]
    (is (:pass? result) (pr-str result))))
