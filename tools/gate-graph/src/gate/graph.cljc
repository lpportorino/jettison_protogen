(ns gate.graph
  "Global execution invariants beyond closed record shapes; no I/O or consumer policy."
  (:require [gate.contract :as c]
            [gate.decimal :as d]
            [gate.interval :as interval]
            [malli.core :as m]))

(def ^:private graph-schema (m/schema c/Graph))
(def MeasurementLookup [:=> [:cat c/Id] [:maybe c/Measurement]])

(defn issue
  "Construct a bounded, schema-described diagnostic referencing graph identities."
  [code subject related]
  {:code code :subject subject :related related})
(m/=> issue [:=> [:cat c/IssueCode c/Id [:maybe c/Id]] c/Issue])

(defn duplicate-ids
  "Return up to 128 repeated identities in deterministic order."
  [ids]
  (->> ids frequencies (keep (fn [[id n]] (when (> n 1) id))) sort (take 128) vec))
(m/=> duplicate-ids
      [:=> [:cat [:vector {:max 1000000} c/Id]] [:vector {:max 128} c/Id]])

(defn acyclic?
  "Check directed arcs in linear graph space; repeated arcs have set semantics."
  [arcs]
  (let [arcs (set arcs)
        vertices (into #{} cat arcs)
        outgoing (reduce (fn [out [a b]] (update out a (fnil conj #{}) b)) {} arcs)
        incoming (merge (zipmap vertices (repeat 0)) (frequencies (map second arcs)))
        ready (vec (sort (keep (fn [[v degree]] (when (zero? degree) v)) incoming)))]
    (loop [queue ready cursor 0 degrees incoming]
      (if (= cursor (count queue))
        (= cursor (count vertices))
        (let [[degrees queue]
              (reduce (fn [[counts pending] target]
                        (let [left (dec (get counts target))]
                          [(assoc counts target left)
                           (if (zero? left) (conj pending target) pending)]))
                      [degrees queue] (get outgoing (nth queue cursor)))]
          (recur queue (inc cursor) degrees))))))
(m/=> acyclic? [:=> [:cat c/Arcs] :boolean])

(defn node-start
  "Return the observed start or decision instant in run-relative nanoseconds."
  [node]
  (if (= :execution (:record node)) (get-in node [:interval :start-ns]) (:at-ns node)))
(m/=> node-start [:=> [:cat c/Node] c/Natural])

(defn node-end
  "Return a finished execution instant, an instantaneous decision, or unknown."
  [node]
  (if (= :execution (:record node)) (get-in node [:interval :end-ns]) (:at-ns node)))
(m/=> node-end [:=> [:cat c/Node] [:maybe c/Natural]])

(defn ordered?
  "An unfinished interval has no asserted upper bound."
  [start end]
  (or (nil? end) (d/before-or-equal? start end)))
(m/=> ordered? [:=> [:cat c/Natural [:maybe c/Natural]] :boolean])

(defn- resolved-endpoint-time
  "Resolve an endpoint only when its phase belongs to that node's record type."
  [node endpoint]
  (when node
    (case (:phase endpoint)
      :decision (when (= :decision (:record node)) (:at-ns node))
      :start (when (= :execution (:record node)) (node-start node))
      :finish (when (= :execution (:record node)) (node-end node)))))
(m/=> resolved-endpoint-time [:=> [:cat [:maybe c/Node] c/Endpoint] [:maybe c/Natural]])

(defn endpoint-time
  "Resolve a public node-index endpoint; record loops resolve owners once before validation."
  [nodes endpoint]
  (resolved-endpoint-time (get nodes (:node endpoint)) endpoint))
(m/=> endpoint-time [:=> [:cat c/NodeIndex c/Endpoint] [:maybe c/Natural]])

(defn endpoint-vertex
  "Injectively encode a node/phase pair; graph identifiers cannot contain '#'."
  [{:keys [node phase]}]
  (str node "#" (name phase)))
(m/=> endpoint-vertex [:=> [:cat c/Endpoint] c/Vertex])

(defn- identity-findings
  "Enforce identity uniqueness within each explicitly typed collection."
  [graph]
  (into [] (take 128)
        (concat
         (mapcat (fn [field]
                   (map #(issue :duplicate-id % nil)
                        (duplicate-ids (mapv :id (get graph field)))))
                 [:nodes :resources :sources :edges :measurements :partitions])
         (map #(issue :duplicate-key % nil)
              (duplicate-ids (get-in graph [:run :expected-keys]))))))
(m/=> identity-findings [:=> [:cat c/Graph] c/Findings])

(defn- source-findings
  "Every observation, node, resource and edge names an available provenance source."
  [graph]
  (let [sources (set (map :id (:sources graph)))]
    (into [] (comp (remove #(contains? sources (:source %)))
                   (map #(issue :missing-source (:id %) (:source %))) (take 128))
          (concat (:nodes graph) (:resources graph) (:edges graph) (:measurements graph)
                  (:partitions graph)))))
(m/=> source-findings [:=> [:cat c/Graph] c/Findings])

(defn- parent-findings
  "Containment is a forest; an instantaneous decision cannot own a running child."
  [parent node]
  (if-let [parent-id (:parent node)]
    (if parent
      (cond
        (= :decision (:record parent)) [(issue :parent-kind (:id node) parent-id)]
        (or (not (d/before-or-equal? (node-start parent) (node-start node)))
            (and (node-end parent)
                 (or (nil? (node-end node))
                     (not (d/before-or-equal? (node-end node) (node-end parent))))))
        [(issue :parent-interval (:id node) parent-id)]
        :else [])
      [(issue :missing-parent (:id node) parent-id)])
    []))
(m/=> parent-findings [:=> [:cat [:maybe c/Node] c/Node] c/Findings])

(defn- node-findings
  "Check execution states and temporal containment without inventing cache durations."
  [run-end parent node]
  (let [id (:id node) end (node-end node)
        execution? (= :execution (:record node))
        expected-reasons {:cached #{:cache-hit} :deselected #{:unchanged}
                          :blocked #{:prerequisite-failed}
                          :refused #{:unsupported :invalid-input :missing-evidence}
                          :cancelled #{:cancellation-requested}}]
    (vec
     (concat
      (parent-findings parent node)
      (when-not (ordered? (node-start node) end) [(issue :interval-order id nil)])
      (when (and run-end (or (not (ordered? (node-start node) run-end))
                             (and end (not (ordered? end run-end)))))
        [(issue :run-interval id nil)])
      (when (and execution? (not= (= :running (:outcome node)) (nil? end)))
        [(issue :execution-state id nil)])
      (when (and (not execution?)
                 (not (contains? (get expected-reasons (:outcome node))
                                 (get-in node [:reason :code]))))
        [(issue :decision-reason id nil)])))))
(m/=> node-findings [:=> [:cat [:maybe c/Natural] [:maybe c/Node] c/Node] c/Findings])

(defn- run-findings
  "A complete run accounts for its declared gate inventory, including decisions."
  [graph]
  (let [run (:run graph)
        gates (filter #(= :gate (:kind %)) (:nodes graph))
        expected (set (:expected-keys run)) actual (set (map :key gates))]
    (into [] (take 128)
          (concat
           (when (and (:complete? run)
                      (or (nil? (:end-ns run)) (some #(= :running (:outcome %)) (:nodes graph))))
             [(issue :run-incomplete (:id run) nil)])
           (when (:complete? run)
             (map #(issue :missing-expected-key % nil) (sort (remove actual expected))))
           (map #(issue :unexpected-key % nil) (sort (remove expected actual)))))))
(m/=> run-findings [:=> [:cat c/Graph] c/Findings])

(defn- forest-findings
  "Reject node/resource parent cycles and dangling resource parents."
  [graph]
  (let [nodes (:nodes graph) resources (:resources graph)
        ids (set (map :id resources))
        node-arcs (into [] (keep #(when (:parent %) [(:parent %) (:id %)])) nodes)
        resource-arcs (into [] (keep #(when (:parent %) [(:parent %) (:id %)])) resources)]
    (into [] (take 128)
          (concat
           (when-not (acyclic? node-arcs) [(issue :parent-cycle (get-in graph [:run :id]) nil)])
           (when-not (acyclic? resource-arcs) [(issue :resource-cycle (get-in graph [:run :id]) nil)])
           (keep #(when (and (:parent %) (not (contains? ids (:parent %))))
                    (issue :missing-parent (:id %) (:parent %))) resources)))))
(m/=> forest-findings [:=> [:cat c/Graph] c/Findings])

(defn- endpoint-findings
  "A missing endpoint differs from a valid but still-unfinished execution."
  [node edge endpoint]
  (if node
    (if (= (= :decision (:record node)) (= :decision (:phase endpoint))) []
        [(issue :endpoint-phase (:id edge) (:node endpoint))])
    [(issue :missing-node (:id edge) (:node endpoint))]))
(m/=> endpoint-findings [:=> [:cat [:maybe c/Node] c/Edge c/Endpoint] c/Findings])

(defn- edge-findings
  "Only observed edges assert temporal causation; declared edges can await evidence."
  [from-node to-node edge]
  (let [from (resolved-endpoint-time from-node (:from edge)) to (resolved-endpoint-time to-node (:to edge))]
    (vec
     (concat
      (endpoint-findings from-node edge (:from edge))
      (endpoint-findings to-node edge (:to edge))
      (when (and (= :observed (:evidence edge)) (or (nil? from) (nil? to)))
        [(issue :edge-unobserved (:id edge) nil)])
      (when (and (= :invalidates (:kind edge)) (= :observed (:evidence edge)))
        [(issue :edge-evidence (:id edge) nil)])
      (when (and (= :observed (:evidence edge)) from to (not (d/before-or-equal? from to)))
        [(issue :edge-time (:id edge) nil)])))))
(m/=> edge-findings [:=> [:cat [:maybe c/Node] [:maybe c/Node] c/Edge] c/Findings])

(defn- causal-findings
  "Check event-phase causality; parent spawn/join must not create a false node cycle."
  [graph]
  (let [internal (for [node (:nodes graph) :when (= :execution (:record node))]
                   [(endpoint-vertex {:node (:id node) :phase :start})
                    (endpoint-vertex {:node (:id node) :phase :finish})])
        external (into [] (comp (remove #(= :invalidates (:kind %)))
                                (map (fn [{:keys [from to]}] [(endpoint-vertex from) (endpoint-vertex to)])))
                       (:edges graph))]
    (if (acyclic? (vec (concat internal external))) []
        [(issue :edge-cycle (get-in graph [:run :id]) nil)])))
(m/=> causal-findings [:=> [:cat c/Graph] c/Findings])

(defn- pmu-valid?
  "Retain raw counts and coverage; multiplexed coverage cannot masquerade as complete."
  [{:keys [method quantity pmu status value reason]}]
  (boolean
   (if-not pmu
     (not= method :pmu)
     (let [{:keys [running-ns enabled-ns raw-value]} pmu]
       (and (= method :pmu) (= quantity :instructions)
            (or (nil? running-ns) (nil? enabled-ns) (ordered? running-ns enabled-ns))
            (or (= status :unavailable)
                (and running-ns enabled-ns raw-value (not= running-ns "0")
                     (case status
                       :measured (and (= running-ns enabled-ns) (= value raw-value))
                       :partial (= value raw-value)
                       :estimated (= reason :multiplexed)))))))))
(m/=> pmu-valid? [:=> [:cat c/Measurement] :boolean])

(defn- measurement-findings
  "Keep unavailable, estimated, partial and measured quantities distinct."
  [run-end owner device measurement]
  (let [{:keys [id node resource quantity form status value reason interval]} measurement
        {:keys [start-ns end-ns]} interval
        state-valid? (case status
                       :measured (and (some? value) (nil? reason))
                       :unavailable (and (nil? value) (some? reason))
                       (and (some? value) (some? reason)))]
    (vec
     (concat
      (when (and node (nil? owner)) [(issue :missing-node id node)])
      (when-not device [(issue :missing-resource id resource)])
      (when-not state-valid? [(issue :measurement-state id nil)])
      (when-not (contains? (:forms (c/quantity-info quantity)) form)
        [(issue :measurement-form id nil)])
      (when (and value (= quantity :gpu-utilization-percent) (pos? (d/compare value "100")))
        [(issue :measurement-value id nil)])
      (when (or (not (ordered? start-ns end-ns)) (and run-end (not (ordered? end-ns run-end)))
                (and owner (or (= :decision (:record owner))
                               (not (ordered? (node-start owner) start-ns))
                               (and (node-end owner) (not (ordered? end-ns (node-end owner)))))))
        [(issue :measurement-interval id node)])
      (when-not (pmu-valid? measurement)
        [(issue :measurement-pmu id nil)])
      (when (and (= :host (:kind device)) (= quantity :pressure-cpu-full-us)
                 (not= status :unavailable))
        [(issue :host-cpu-full id resource)])))))
(m/=> measurement-findings
      [:=> [:cat [:maybe c/Natural] [:maybe c/Node] [:maybe c/Resource] c/Measurement] c/Findings])

(defn- partition-findings
  "Check a producer assertion's internal consistency; provenance is still the producer's obligation."
  [measurements assertion]
  (let [{:keys [id members quantity]} assertion
        observations (keep measurements members)
        groups (sort-by key (group-by :resource observations))]
    (into [] (take 128)
          (concat
           (map #(issue :partition-member id %) (duplicate-ids members))
           (map #(issue :partition-member id %) (remove measurements members))
           (when-not (:additive? (c/quantity-info quantity))
             [(issue :partition-quantity id nil)])
           (for [observation observations
                 :when (or (not= quantity (:quantity observation))
                           (not= :delta (:form observation)))]
             (issue :partition-quantity id (:id observation)))
           (for [observation observations :when (not= :exclusive (:accounting observation))]
             (issue :partition-accounting id (:id observation)))
           (mapcat
            (fn [[_ group]]
              (let [ordered (sort (fn [a b]
                                    (d/compare (get-in a [:interval :start-ns])
                                               (get-in b [:interval :start-ns]))) group)]
                (for [[a b] (partition-all 2 1 ordered)
                      :when (and b
                                 (ordered? (get-in a [:interval :start-ns]) (get-in a [:interval :end-ns]))
                                 (ordered? (get-in b [:interval :start-ns]) (get-in b [:interval :end-ns]))
                                 (not (interval/separated? (:interval a) (:interval b))))]
                  (issue :partition-overlap id (:id b))))) groups)))))
(m/=> partition-findings [:=> [:cat MeasurementLookup c/Partition] c/Findings])

(defn findings
  "Return at most 128 named findings; nonempty always means this graph is invalid.
   Validate full collections at entry, then pass resolved records to local checks so Malli
   instrumentation does not rescan the whole graph for every node, edge or measurement."
  [graph]
  (if-not (m/validate graph-schema graph)
    [(issue :shape "graph" nil)]
    (let [nodes (into {} (map (juxt :id identity)) (:nodes graph))
          resources (into {} (map (juxt :id identity)) (:resources graph))
          measurements (into {} (map (juxt :id identity)) (:measurements graph))
          lookup-measurement #(get measurements %)
          run-end (get-in graph [:run :end-ns])]
      (into [] (take 128)
            (concat (identity-findings graph) (source-findings graph) (run-findings graph)
                    (forest-findings graph)
                    (mapcat #(node-findings run-end (get nodes (:parent %)) %) (:nodes graph))
                    (mapcat #(edge-findings (get nodes (get-in % [:from :node]))
                                            (get nodes (get-in % [:to :node])) %) (:edges graph))
                    (causal-findings graph)
                    (mapcat #(measurement-findings run-end (get nodes (:node %))
                                                   (get resources (:resource %)) %) (:measurements graph))
                    (mapcat #(partition-findings lookup-measurement %) (:partitions graph)))))))
(m/=> findings [:=> [:cat c/Graph] c/Findings])

(defn require-valid!
  "Validate a graph before traversal or aggregation; preserve structured diagnostics."
  [graph]
  (let [errors (findings graph)]
    (when (seq errors)
      (throw (ex-info "Invalid execution graph" {:code :invalid-graph :findings errors})))
    graph))
(m/=> require-valid! [:=> [:cat c/Graph] c/Graph])
