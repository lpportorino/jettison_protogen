(ns gate.measure
  "Bounded arithmetic over explicit observations with auditable disjointness evidence."
  (:require [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as d]
            [gate.inspection-contract :as ic]
            [gate.interval :as interval]
            [malli.core :as m]))

(def Observations [:vector {:min 1 :max 256} c/Measurement])
(def Evidence [:vector {:max 32} [:tuple c/Id [:set {:min 2 :max 256} c/Id]]])
(def Proof
  [:map {:closed true} [:checked-pairs [:int {:min 0 :max 32640}]]
   [:partitions [:vector {:max 32} c/Id]] [:failure [:maybe ic/AggregateRefusal]]])
(def ^:private request-schema (m/schema ic/AggregateRequest))

(defn- compatible?
  "Require matching instrument, provenance, units, form and accounting semantics."
  [a b]
  (and (= (select-keys a [:quantity :form :accounting :method :source])
          (select-keys b [:quantity :form :accounting :method :source]))
       (= (select-keys (:pmu a) [:event :domain])
          (select-keys (:pmu b) [:event :domain]))))
(m/=> compatible? [:=> [:cat c/Measurement c/Measurement] :boolean])

(defn- admission
  "Refuse the entire selection rather than silently dropping missing or uncertain data."
  [context request]
  (let [ids (:measurements request)
        observations (mapv (:measurement-index context) ids)
        first-observation (first observations)]
    (or
     (some (fn [id]
             (when-not (contains? (:measurement-index context) id)
               {:code :missing-measurement :subjects [id]})) ids)
     (some (fn [id]
             (when-not (contains? (:partition-index context) id)
               {:code :missing-partition :subjects [id]})) (:partitions request))
     (some (fn [observation]
             (when-not (= :measured (:status observation))
               {:code :uncertain-observation :subjects [(:id observation)]})) observations)
     (some (fn [observation]
             (when-not (compatible? first-observation observation)
               {:code :incompatible-observations
                :subjects [(:id first-observation) (:id observation)]})) (rest observations))
     (when (or (= :counter (:form first-observation))
               (and (= :sum (:op request)) (not= :delta (:form first-observation))))
       {:code :unsupported-form :subjects [(:id first-observation)]})
     (when (and (= :sum (:op request))
                (not (:additive? (c/quantity-info (:quantity first-observation)))))
       {:code :non-additive :subjects [(:id first-observation)]}))))
(m/=> admission [:=> [:cat ic/Prepared ic/AggregateRequest] [:maybe ic/AggregateRefusal]])

(defn- prove-pairs
  "Bound pair checks; each overlap needs one explicit assertion covering both observations."
  [observations evidence max-pairs]
  (loop [i 0 j 1 checked 0 used #{}]
    (let [base {:checked-pairs checked :partitions (vec (sort used))}]
      (cond
        (>= i (dec (count observations))) (assoc base :failure nil)
        (= j (count observations)) (recur (inc i) (+ i 2) checked used)
        :else
        (let [a (nth observations i) b (nth observations j)
              subjects [(:id a) (:id b)]]
          (if (= checked max-pairs)
            (assoc base :failure {:code :pair-limit :subjects subjects})
            (if (interval/separated? (:interval a) (:interval b))
              (recur i (inc j) (inc checked) used)
              (if-let [assertion (some (fn [[id members]]
                                         (when (and (contains? members (:id a))
                                                    (contains? members (:id b))) id)) evidence)]
                (recur i (inc j) (inc checked) (conj used assertion))
                (assoc base :checked-pairs (inc checked)
                       :failure {:code :overlap-unproven :subjects subjects})))))))))
(m/=> prove-pairs
      [:=> [:cat Observations Evidence [:int {:min 0 :max 32640}]] Proof])

(defn- exact-value
  "No floats, counter subtraction, uncertainty imputation or RSS summation."
  [op observations]
  (if (= :sum op)
    (reduce (fn [total observation] (d/add total (:value observation))) "0" observations)
    (reduce (fn [maximum observation]
              (if (pos? (d/compare (:value observation) maximum)) (:value observation) maximum))
            "0" observations)))
(m/=> exact-value [:=> [:cat ic/AggregateOp Observations] c/Natural])

(defn- evaluate
  "Separate admission, overlap proof and arithmetic so budget exhaustion cannot emit a total."
  [context request]
  (let [base {:artifact (:artifact context) :op (:op request) :scope :selected-observations
              :measurements (:measurements request) :checked-pairs 0}]
    (if-let [refusal (admission context request)]
      (merge base {:status :refused} refusal)
      (let [observations (mapv (:measurement-index context) (:measurements request))
            evidence (mapv (fn [id]
                             [id (set (:members (get (:partition-index context) id)))])
                           (:partitions request))
            proof (if (= :sum (:op request))
                    (prove-pairs observations evidence (get-in request [:budget :pairs]))
                    {:checked-pairs 0 :partitions [] :failure nil})
            base (assoc base :checked-pairs (:checked-pairs proof))]
        (if-let [failure (:failure proof)]
          (merge base {:status :refused} failure)
          (try
            (merge base {:status :complete :quantity (:quantity (first observations))
                         :value (exact-value (:op request) observations)
                         :basis (cond
                                  (= :maximum-observation (:op request)) :observation-extremum
                                  (= 1 (count observations)) :single-observation
                                  (seq (:partitions proof)) :attested-disjointness
                                  :else :time-disjoint)
                         :partitions (:partitions proof)})
            (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
              (if (= :quantity-overflow (:code (ex-data error)))
                (merge base {:status :refused :code :quantity-overflow :subjects []})
                (throw error)))))))))
(m/=> evaluate [:=> [:cat ic/Prepared ic/AggregateRequest] ic/AggregateResult])

(defn aggregate
  "Aggregate explicitly named measurements in a Prepared graph under an AggregateRequest budget.
   :sum accepts compatible measured deltas only; overlapping work needs provenance-bearing disjointness
   assertions named in :partitions. :maximum-observation reports one observed extremum, never a
   simultaneous resource total. Inclusive CPU, RSS peaks, counters and uncertain values cannot be
   silently summed, differenced or imputed. :budget bounds pair checks and total response bytes.
   A complete result preserves the exact value, quantity, selected IDs, artifact digest and proof basis.
   Missing/incompatible/uncertain observations, unknown overlap, overflow or exhausted pair budgets
   return a closed :refused result with subjects and no partial value. Malformed requests, duplicate
   selections and output-byte exhaustion throw named failures. This API does not discover a selection."
  [context request]
  (when-not (and (m/validate request-schema request)
                 (= (count (:measurements request)) (count (set (:measurements request))))
                 (= (count (:partitions request)) (count (set (:partitions request)))))
    (throw (ex-info "Invalid aggregation request" {:code :invalid-aggregation})))
  (let [request (cond-> (update request :measurements #(vec (sort %)))
                  (contains? request :partitions) (update :partitions #(vec (sort %))))
        answer (evaluate context request)]
    (canonical/encode answer (get-in request [:budget :bytes]))
    answer))
(m/=> aggregate [:=> [:cat ic/Prepared ic/AggregateRequest] ic/AggregateResult])
