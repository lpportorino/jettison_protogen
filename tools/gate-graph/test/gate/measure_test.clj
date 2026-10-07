(ns gate.measure-test
  "Independent overlap/arithmetic oracles and named refusals for aggregation."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as check]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.fixtures :as f]
            [gate.graph :as graph]
            [gate.inspection-contract :as ic]
            [gate.measure :as measure]
            [gate.query :as query]
            [malli.core :as m]))

(defn observation [id resource start end value]
  (merge (f/measurement)
         {:id id :node "root" :resource resource :value value
          :interval {:start-ns start :end-ns end}}))

(defn document [observations]
  (assoc (f/example) :measurements observations
         :resources (into (:resources (f/example))
                          (for [resource (sort (disj (set (map :resource observations)) "host" "worker"))]
                            {:id resource :parent "host" :source "capture" :kind :process
                             :label "Synthetic resource"}))))

(defn request [op ids]
  {:op op :measurements ids :budget {:pairs 32640 :bytes 65536}})

(defn assertion [members]
  {:id "partition" :source "capture" :quantity :cpu-user-ns :members members})

(defn error-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))

(deftest serial-work-is-exact-and-does-not-claim-run-coverage
  (let [context (query/prepare (document [(observation "a" "worker" "0" "40" "80")
                                          (observation "b" "worker" "40" "100" "120")]))
        answer (measure/aggregate context (request :sum ["b" "a"]))]
    (is (= {:artifact (:artifact context) :op :sum :scope :selected-observations
            :measurements ["a" "b"] :checked-pairs 1 :status :complete
            :quantity :cpu-user-ns :value "200" :basis :time-disjoint :partitions []}
           answer))
    (is (= answer (edn/read-string (canonical/encode answer 65536))))
    (is (= answer (measure/aggregate context (request :sum ["a" "b"]))))))

(deftest concurrent-inclusive-and-unattested-exclusive-work-cannot-be-added
  (doseq [accounting [:inclusive :exclusive :shared]]
    (let [observations (mapv #(assoc % :accounting accounting)
                             [(observation "a" "worker" "0" "60" "100")
                              (observation "b" "other" "40" "100" "100")])
          result (measure/aggregate (query/prepare (document observations)) (request :sum ["a" "b"]))]
      (is (= :refused (:status result)))
      (is (= :overlap-unproven (:code result)))
      (is (= ["a" "b"] (:subjects result)))
      (is (not (contains? result :value))))))

(deftest concurrent-sum-retains-explicit-assertion-identity
  (let [observations (mapv #(assoc % :accounting :exclusive)
                           [(observation "a" "worker" "0" "100" "140")
                            (observation "b" "other" "0" "100" "130")])
        graph-document (assoc (document observations) :partitions [(assertion ["b" "a"])])
        context (query/prepare graph-document)
        result (measure/aggregate context (assoc (request :sum ["b" "a"]) :partitions ["partition"]))]
    (is (= :complete (:status result)))
    (is (= "270" (:value result)))
    (is (= :attested-disjointness (:basis result)))
    (is (= ["partition"] (:partitions result)))
    (is (= (:artifact context)
           (:artifact (query/prepare (assoc-in graph-document [:partitions 0 :members] ["a" "b"])))))
    (is (not= (:artifact context)
              (:artifact (query/prepare (dissoc graph-document :partitions)))))))

(deftest internally-contradictory-assertions-name-their-failure
  (let [observations (mapv #(assoc % :accounting :exclusive)
                           [(observation "a" "worker" "0" "60" "100")
                            (observation "b" "other" "40" "100" "100")])
        valid (assoc (document observations) :partitions [(assertion ["a" "b"])])]
    (is (= [] (graph/findings valid)))
    (doseq [[path value code]
            [[[:partitions 0 :members] ["a" "a"] :partition-member]
             [[:partitions 0 :members] ["a" "missing"] :partition-member]
             [[:partitions 0 :source] "missing" :missing-source]
             [[:partitions 0 :quantity] :tests :partition-quantity]
             [[:measurements 0 :accounting] :inclusive :partition-accounting]
             [[:measurements 0 :accounting] :shared :partition-accounting]
             [[:measurements 1 :resource] "worker" :partition-overlap]]]
      (let [mutant (assoc-in valid path value)]
        (is (m/validate c/Graph mutant))
        (is (some #(= code (:code %)) (graph/findings mutant)) (pr-str path))))))

(deftest unknown-and-imprecise-values-refuse-without-zero-imputation
  (doseq [changes [{:status :unavailable :reason :not-collected :value nil}
                   {:status :partial :reason :partial-coverage}
                   {:status :estimated :reason :sampled-lower-bound}]]
    (let [context (query/prepare (document [(merge (observation "a" "worker" "0" "40" "80") changes)]))
          result (measure/aggregate context (request :sum ["a"]))]
      (is (= :uncertain-observation (:code result)))
      (is (not (contains? result :value)))))
  (let [context (query/prepare (f/example))]
    (is (= :missing-measurement (:code (measure/aggregate context (request :sum ["missing"])))))))

(deftest incompatible-methods-domains-and-provenance-refuse
  (let [a (observation "a" "worker" "0" "40" "80")
        b (observation "b" "other" "40" "100" "90")]
    (doseq [changes [{:method :cgroup} {:quantity :cpu-system-ns} {:accounting :exclusive}
                     {:source "other-source"}]]
      (let [graph-document (-> (document [a (merge b changes)])
                               (update :sources conj (assoc (first (:sources (f/example))) :id "other-source")))
            answer (measure/aggregate (query/prepare graph-document) (request :sum ["a" "b"]))]
        (is (= :incompatible-observations (:code answer)) (pr-str changes))))
    (let [pmu {:method :pmu :quantity :instructions
               :pmu {:event "instructions" :domain :user :enabled-ns "40"
                     :running-ns "40" :raw-value "80"}}
          a (merge a pmu)
          b (-> (merge b pmu) (assoc :value "80") (assoc-in [:pmu :domain] :user-kernel))]
      (is (= :incompatible-observations
             (:code (measure/aggregate (query/prepare (document [a b])) (request :sum ["a" "b"]))))))))

(deftest peaks-and-gauges-have-observation-extrema-never-sums
  (doseq [[quantity form] [[:rss-peak-bytes :peak] [:memory-current-bytes :gauge]]]
    (let [observations (mapv #(assoc % :quantity quantity :form form)
                             [(observation "a" "worker" "0" "100" "800")
                              (observation "b" "other" "0" "100" "1200")])
          context (query/prepare (document observations))
          maximum (measure/aggregate context (request :maximum-observation ["a" "b"]))]
      (is (= :unsupported-form (:code (measure/aggregate context (request :sum ["a" "b"])))))
      (is (= "1200" (:value maximum)))
      (is (= :observation-extremum (:basis maximum)))
      (is (= 0 (:checked-pairs maximum))))))

(deftest counters-and-pressure-are-not-additive-work
  (doseq [[changes reason] [[{:form :counter} :unsupported-form]
                            [{:quantity :pressure-cpu-some-us :method :psi} :non-additive]]]
    (let [context (query/prepare (document [(merge (observation "a" "worker" "0" "40" "80") changes)]))]
      (is (= reason (:code (measure/aggregate context (request :sum ["a"]))))))))

(deftest budgets-do-not-return-a-partial-total
  (let [context (query/prepare (document [(observation "a" "worker" "0" "30" "80")
                                          (observation "b" "worker" "30" "60" "90")
                                          (observation "c" "worker" "60" "100" "100")]))
        input (request :sum ["a" "b" "c"])
        refused (measure/aggregate context (assoc-in input [:budget :pairs] 2))]
    (is (= :pair-limit (:code refused)))
    (is (= 2 (:checked-pairs refused)))
    (is (not (contains? refused :value)))
    (is (= "270" (:value (measure/aggregate context (assoc-in input [:budget :pairs] 3))))))
  (let [ids (mapv #(str "measurement-" % "-" (apply str (repeat 140 "x"))) (range 10))
        observations (mapv #(observation % "worker" "0" "40" "80") ids)
        context (query/prepare (document observations))]
    (is (= :encoded-byte-limit
           (error-code #(measure/aggregate context
                                           (assoc-in (request :maximum-observation ids) [:budget :bytes] 1024)))))))

(deftest duplicates-missing-assertions-and-extra-input-fields-refuse
  (let [context (query/prepare (document [(observation "a" "worker" "0" "40" "80")]))]
    (is (= :invalid-aggregation (error-code #(measure/aggregate context (request :sum ["a" "a"])))))
    (is (= :missing-partition
           (:code (measure/aggregate context (assoc (request :sum ["a"]) :partitions ["missing"])))))
    (is (false? (m/validate ic/AggregateRequest (assoc (request :sum ["a"]) :eval '(+ 1 2)))))
    (is (false? (m/validate ic/AggregateRequest (request :sum []))))))

(deftest exact-quantity-overflow-refuses-the-total
  (let [largest (apply str (repeat 40 "9"))
        context (query/prepare (document [(observation "a" "worker" "0" "40" largest)
                                          (observation "b" "worker" "40" "100" "1")]))
        answer (measure/aggregate context (request :sum ["a" "b"]))]
    (is (= :quantity-overflow (:code answer)))
    (is (not (contains? answer :value)))))

(deftest coincident-instant-measurements-need-accounting-evidence
  (let [context (query/prepare (document [(observation "a" "worker" "40" "40" "1")
                                          (observation "b" "other" "40" "40" "1")]))]
    (is (= :overlap-unproven (:code (measure/aggregate context (request :sum ["a" "b"])))))))

(deftest overlap-and-exact-sums-agree-with-independent-point-set-oracle
  (let [property
        (prop/for-all [intervals (gen/vector (gen/tuple (gen/choose 0 80) (gen/choose 1 20)
                                                        (gen/choose 0 1000000)) 1 8)]
                      (let [observations (mapv (fn [i [start length value]]
                                                 (observation (str "m" i) "worker" (str start) (str (+ start length))
                                                              (str (+ 9007199254740993N value))))
                                               (range) intervals)
                            cells (map (fn [[start length _]] (set (range start (+ start length)))) intervals)
                            disjoint? (= (reduce + (map count cells)) (count (apply set/union cells)))
                            answer (measure/aggregate (query/prepare (document observations))
                                                      (request :sum (mapv :id observations)))]
                        (if disjoint?
                          (and (= :complete (:status answer))
                               (= (str (reduce + (map (comp bigint :value) observations))) (:value answer)))
                          (= :overlap-unproven (:code answer)))))
        result (check/quick-check 300 property :seed 161803)]
    (is (:pass? result) (pr-str result))))
