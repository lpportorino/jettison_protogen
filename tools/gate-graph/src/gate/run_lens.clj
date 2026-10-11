(ns gate.run-lens
  "Optional compact attention and measured interval views; structural browsing retains every record field."
  (:require [gate.archive :as archive]
            [gate.archive-contract :as archive-contract]
            [gate.canonical :as canonical]
            [gate.contract :as graph-contract]
            [gate.decimal :as decimal]
            [gate.query :as query]
            [gate.record-view-contract :as record]
            [gate.run-lens-contract :as lc]
            [malli.core :as m]))

(defn- count-observations
  "Count every record and retry; distinct keys are a separate producer inventory."
  [nodes]
  {:observations (count nodes) :unique-keys (count (set (map :key nodes)))
   :executions (count (filter #(= :execution (:record %)) nodes))
   :decisions (count (filter #(= :decision (:record %)) nodes))
   :outcomes (merge (zipmap (distinct (concat (rest graph-contract/ExecutionOutcome)
                                              (rest graph-contract/DecisionOutcome))) (repeat 0))
                    (frequencies (map :outcome nodes)))})
(m/=> count-observations [:=> [:cat [:vector {:max 128000} graph-contract/Node]] lc/observation-counts-schema])

(defn- compact-acquisition
  "Keep observed content identity and root revision, without transitive repository listings."
  [acquisition]
  (if (= :observed (:state acquisition))
    (let [snapshot (:snapshot acquisition)]
      {:state :observed :profile (:profile snapshot) :content (:content snapshot)
       :repository-count (count (:repositories snapshot))
       :root (first (filter #(= "." (:path %)) (:repositories snapshot)))})
    acquisition))
(m/=> compact-acquisition [:=> [:cat archive-contract/Acquisition] lc/acquisition-summary-schema])

(defn- compact-run
  "Report exact run-clock elapsed time only when the producer recorded its end."
  [run]
  (assoc (dissoc run :expected-keys) :elapsed-ns (:end-ns run) :elapsed-basis :run-relative-end))
(m/=> compact-run [:=> [:cat graph-contract/Run] lc/compact-run-schema])

(defn- gate-duration
  "Project one closed gate execution; keep attempt, identity and exact string nanoseconds."
  [node path]
  (let [{:keys [start-ns end-ns]} (:interval node)]
    (assoc (select-keys node [:id :key :label :attempt :outcome])
           :start-ns start-ns :end-ns end-ns :elapsed-ns (decimal/subtract end-ns start-ns) :path path)))
(m/=> gate-duration [:=> [:cat graph-contract/Execution record/Path] lc/gate-duration-schema])

(defn- duration-order
  "Rank exact elapsed nanoseconds descending; use stable record IDs for equal durations."
  [a b]
  (let [duration (decimal/compare (:elapsed-ns b) (:elapsed-ns a))]
    (if (zero? duration) (clojure.core/compare (:id a) (:id b)) duration)))
(m/=> duration-order [:=> [:cat lc/gate-duration-schema lc/gate-duration-schema] :int])

(defn- ranked-gates
  "Rank closed gate intervals only, preserving open execution counts and omitted rows."
  [nodes top]
  (let [executions (filter (fn [[_ node]] (and (= :gate (:kind node)) (= :execution (:record node)))) (map-indexed vector nodes))
        closed (filter (fn [[_ node]] (some? (get-in node [:interval :end-ns]))) executions)
        rows (vec (take top (sort duration-order (map (fn [[index node]] (gate-duration node [:graph :nodes index])) closed))))]
    {:basis :closed-gate-execution-intervals :closed (count closed)
     :open (- (count executions) (count closed)) :omitted (- (count closed) (count rows)) :rows rows}))
(m/=> ranked-gates [:=> [:cat [:vector {:max 128000} graph-contract/Node] [:int {:min 1 :max 100}]]
                    lc/ranked-gates-schema])

(defn- measurement-coverage
  "Count recorded resource observations; absent CPU and causal attribution remain unknown."
  [graph]
  {:resources (count (:resources graph)) :observations (count (:measurements graph))
   :collection (if (seq (:measurements graph)) :present :none)
   :statuses (merge {:measured 0 :partial 0 :estimated 0 :unavailable 0}
                    (frequencies (map :status (:measurements graph))))
   :cpu :unknown :critical-path :unknown :task-attribution :unknown})
(m/=> measurement-coverage [:=> [:cat graph-contract/Graph] lc/measurement-summary-schema])

(defn- producer-counts
  "Compare producer-declared gate keys with gate records; other kinds remain separate."
  [graph]
  (let [expected (set (get-in graph [:run :expected-keys]))
        observed (set (map :key (:nodes graph)))
        gate-keys (set (map :key (filter #(= :gate (:kind %)) (:nodes graph))))
        matched (count (filter gate-keys expected))]
    {:expected-keys (count expected) :observed-expected-keys matched
     :missing-expected-keys (- (count expected) matched)
     :other-observed-keys (- (count observed) matched)}))
(m/=> producer-counts [:=> [:cat graph-contract/Graph] lc/producer-counts-schema])

(defn- attention
  "Retain every attention observation count, actual gate count and stable capped drill routes."
  [nodes top]
  (let [selected (filter (fn [[_ node]] (contains? #{:failed :error :cancelled :running :blocked :refused} (:outcome node)))
                         (map-indexed vector nodes))
        rows (vec (take top (sort-by :id (map (fn [[index node]]
                                                (assoc (select-keys node [:id :key :label :kind :record :outcome])
                                                       :path [:graph :nodes index])) selected))))]
    {:observations (count selected) :gates (count (filter (fn [[_ node]] (= :gate (:kind node))) selected))
     :omitted (- (count selected) (count rows)) :rows rows}))
(m/=> attention [:=> [:cat [:vector {:max 128000} graph-contract/Node] [:int {:min 1 :max 100}]] lc/attention-summary])

(defn- sections
  "Expose all actual archive root fields as typed routes; containers expose exact immediate child counts."
  [document]
  (mapv (fn [field] (let [value (get document field)]
                      {:path [field] :kind (cond (map? value) :map (vector? value) :vector :else :leaf)
                       :children (if (or (map? value) (vector? value)) (count value) 0)}))
        (sort-by str (keys document))))
(m/=> sections [:=> [:cat archive-contract/Document] [:vector {:max 16} lc/section-summary]])

(defn digest
  "Project one whole verified archive with optional top-N attention and gate duration entry points.
   Exact counts retain retries, decisions and open intervals. Each selected row points into the complete
   structural record. Omitted rows are explicit; this index is not recursive coverage or a lazy reader.
   Native clock origin is preserved, elapsed uses the run-relative end, and overlapping intervals are
   never summed. No CPU, critical path, attribution, full CI or producer honesty is inferred.
   The canonical EDN UTF-8 body fits the explicit byte budget or refuses; no metadata is silently dropped."
  [document budget]
  (when-not (m/validate lc/digest-budget budget)
    (throw (ex-info "Invalid run digest budget" {:code :invalid-digest-budget})))
  (archive/require-valid! document)
  (let [context (query/prepare (:graph document)) graph (:graph context)
        gate-nodes (filterv #(= :gate (:kind %)) (:nodes graph))
        result {:artifact (:artifact document) :graph-artifact (:artifact context)
                :scope (:scope document) :status (:status document) :run (compact-run (:run graph))
                :provenance {:stability (get-in document [:provenance :stability])
                             :before (compact-acquisition (get-in document [:provenance :before]))
                             :after (compact-acquisition (get-in document [:provenance :after]))}
                :producer (producer-counts graph)
                :observations (count-observations (:nodes graph)) :gates (count-observations gate-nodes)
                :longest-gates (ranked-gates (:nodes graph) (:top budget))
                :measurements (measurement-coverage graph)
                :attention (attention (:nodes graph) (:top budget)) :sections (sections document)}]
    (canonical/encode result (:bytes budget))
    result))
(m/=> digest [:=> [:cat archive-contract/Document lc/digest-budget] lc/digest-result])
