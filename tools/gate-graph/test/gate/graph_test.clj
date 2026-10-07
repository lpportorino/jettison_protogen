(ns gate.graph-test
  "Named negative graph fixtures distinguish shape, reference and causal failures."
  (:require [clojure.test :refer [deftest is testing]]
            [gate.contract :as c]
            [gate.fixtures :as f]
            [gate.graph :as g]
            [gate.schema :as schema]
            [malli.core :as m]))

(deftest complete-execution-and-greater-than-wall-cpu-are-valid
  (is (= [] (g/findings (f/example))))
  (is (= [] (g/findings (assoc (f/example) :measurements [(f/measurement)])))))

(deftest decisions-have-a-distinct-phase-and-no-fabricated-interval
  (let [graph (assoc-in (f/decision) [:edges 0 :to :phase] :decision)]
    (is (= [] (g/findings graph)))
    (is (false? (m/validate c/Graph (assoc-in graph [:nodes 2 :interval]
                                              {:start-ns "40" :end-ns "40"}))))))

(deftest nested-shapes-refuse-unregistered-fields
  (doseq [path [[:foreign] [:run :foreign] [:run :clock :foreign]
                [:sources 0 :foreign] [:resources 0 :foreign]
                [:nodes 0 :foreign] [:nodes 0 :interval :foreign]
                [:edges 0 :foreign] [:edges 0 :from :foreign]]]
    (is (false? (m/validate c/Graph (assoc-in (f/example) path true))) (pr-str path))))

(deftest normalized-registry-has-no-open-map-or-untyped-hole
  (doseq [[schema-name schema] schema/registry
          form (tree-seq coll? seq schema)]
    (is (not (contains? #{:any :some 'any? 'some?} form)) (str schema-name " has an untyped hole"))
    (when (and (vector? form) (= :map (first form)))
      (is (= true (:closed (second form))) (str schema-name " has an open map")))))

(deftest independent-invariant-mutations-name-the-intended-clause
  (doseq [[path value clause]
          [[[:nodes 1 :id] "root" :duplicate-id]
           [[:nodes 1 :source] "missing" :missing-source]
           [[:nodes 1 :parent] "missing" :missing-parent]
           [[:nodes 0 :parent] "compile" :parent-cycle]
           [[:resources 0 :parent] "host" :resource-cycle]
           [[:nodes 1 :interval :end-ns] "101" :parent-interval]
           [[:nodes 1 :interval :start-ns] "41" :interval-order]
           [[:nodes 1 :outcome] :running :execution-state]
           [[:run :expected-keys] ["compile" "check" "absent"] :missing-expected-key]
           [[:run :expected-keys] ["compile"] :unexpected-key]
           [[:edges 0 :to :node] "missing" :missing-node]
           [[:edges 0 :to :phase] :decision :endpoint-phase]
           [[:edges 0 :from :node] "check" :edge-time]]]
    (let [original (f/example) changed (assoc-in original path value)]
      (is (not= original changed) "The mutation must land")
      (is (m/validate c/Graph changed) "Only a global invariant changes")
      (is (some #(= clause (:code %)) (g/findings changed)) (str path " -> " clause)))))

(deftest spawn-and-join-form-a-valid-event-dag
  (let [graph (assoc (f/example) :edges
                     [{:id "spawn" :from {:node "root" :phase :start}
                       :to {:node "compile" :phase :start} :kind :spawn :source "capture" :evidence :observed}
                      {:id "join" :from {:node "compile" :phase :finish}
                       :to {:node "root" :phase :finish} :kind :join :source "capture" :evidence :observed}])]
    (is (= [] (g/findings graph)))
    (is (some #(= :edge-cycle (:code %))
              (g/findings (assoc-in graph [:edges 1 :to :phase] :start))))))

(deftest incomplete-is-valid-data-but-cannot-claim-completion
  (let [graph (-> (f/example)
                  (assoc-in [:run :complete?] false) (assoc-in [:run :end-ns] nil)
                  (assoc-in [:nodes 0 :outcome] :running) (assoc-in [:nodes 0 :interval :end-ns] nil)
                  (assoc-in [:nodes 2 :outcome] :running) (assoc-in [:nodes 2 :interval :end-ns] nil))]
    (is (= [] (g/findings graph)))
    (is (some #(= :run-incomplete (:code %)) (g/findings (assoc-in graph [:run :complete?] true))))))

(deftest declared-dependencies-do-not-invent-time-order-and-invalidation-is-not-observed-causation
  (let [reversed (-> (f/example)
                     (assoc-in [:edges 0 :from :node] "check")
                     (assoc-in [:edges 0 :to :node] "compile"))]
    (is (not-any? #(= :edge-cycle (:code %)) (g/findings reversed)))
    (is (some #(= :edge-time (:code %)) (g/findings reversed)))
    (is (empty? (g/findings (assoc-in reversed [:edges 0 :evidence] :declared)))))
  (let [invalidates (assoc-in (f/example) [:edges 0 :kind] :invalidates)]
    (is (some #(= :edge-evidence (:code %)) (g/findings invalidates)))
    (is (empty? (g/findings (assoc-in invalidates [:edges 0 :evidence] :declared))))))

(deftest measurement-states-and-capabilities-are-not-zero-filled
  (doseq [[changes code]
          [[{:status :unavailable} :measurement-state]
           [{:resource "missing"} :missing-resource]
           [{:form :peak} :measurement-form]
           [{:quantity :gpu-utilization-percent :form :gauge :value "101"} :measurement-value]
           [{:quantity :pressure-cpu-full-us :method :psi :resource "host"} :host-cpu-full]
           [{:method :pmu} :measurement-pmu]]]
    (testing (str code)
      (let [graph (assoc (f/example) :measurements [(merge (f/measurement) changes)])]
        (is (m/validate c/Graph graph))
        (is (some #(= code (:code %)) (g/findings graph)))))))

(deftest missing-pmu-service-is-unavailable
  (let [measurement (merge (f/measurement)
                           {:method :pmu :quantity :instructions :status :unavailable
                            :reason :not-collected :value nil
                            :pmu {:event "instructions" :domain :user :enabled-ns "40"
                                  :running-ns "0" :raw-value nil}})]
    (is (= [] (g/findings (assoc (f/example) :measurements [measurement]))))))

(deftest multiplexed-instructions-retain-the-raw-count-and-partial-coverage
  (let [raw (merge (f/measurement)
                   {:method :pmu :quantity :instructions :value "5"
                    :pmu {:event "instructions" :domain :user :enabled-ns "40"
                          :running-ns "20" :raw-value "5"}})
        partial-observation (assoc raw :status :partial :reason :partial-coverage)
        estimate (assoc raw :status :estimated :reason :multiplexed :value "10")]
    (is (some #(= :measurement-pmu (:code %))
              (g/findings (assoc (f/example) :measurements [raw]))))
    (is (= [] (g/findings (assoc (f/example) :measurements [partial-observation]))))
    (is (= [] (g/findings (assoc (f/example) :measurements [estimate]))))))
