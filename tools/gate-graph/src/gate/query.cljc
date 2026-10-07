(ns gate.query
  "Bounded data-only node/measurement drilling with graph/selection-bound continuation records."
  (:require [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as d]
            [gate.graph :as graph]
            [gate.inspection-contract :as ic]
            [malli.core :as m]))

(def ^:private request-schema (m/schema ic/Request))

(defn prepare
  "Validate and normalize a Graph once, returning its immutable Prepared context and full artifact digest.
   Keep the context locally: it contains indexes and must never be accepted as caller-supplied evidence.
   Preparation is bounded by graph limits, not by the later per-page query budget. Invalid global
   invariants throw closed :invalid-graph findings; no incomplete capture is promoted to complete."
  [value]
  (let [graph (canonical/normalize-graph (graph/require-valid! value))]
    {:artifact (canonical/sha256 (canonical/encode graph 134217728))
     :graph graph :ordered-nodes (vec (sort-by :id (:nodes graph)))
     :node-ids (set (map :id (:nodes graph)))
     :measurement-index (into {} (map (juxt :id identity)) (:measurements graph))
     :partition-index (into {} (map (juxt :id identity)) (:partitions graph))}))
(m/=> prepare [:=> [:cat c/Graph] ic/Prepared])

(defn- candidates
  "Select the indexed inventory; no full measurement scan precedes a page's visit budget."
  [context selection]
  (if (= :measurements (:op selection))
    (get-in context [:graph :measurements]) (:ordered-nodes context)))
(m/=> candidates [:=> [:cat ic/Prepared ic/Selection] [:vector {:max 1000000} ic/Row]])

(defn- in-window?
  "Intersect half-open time windows, including instantaneous decisions and open executions."
  [node interval]
  (let [{:keys [start-ns end-ns]} interval
        start (graph/node-start node) end (graph/node-end node)]
    (and (neg? (d/compare start-ns end-ns))
         (neg? (d/compare start end-ns))
         (if (= start end)
           (d/before-or-equal? start-ns start)
           (or (nil? end) (pos? (d/compare end start-ns)))))))
(m/=> in-window? [:=> [:cat c/Node c/Interval] :boolean])

(defn- selected?
  "Apply only registered selectors and equality filters; never evaluate expressions."
  [node selection]
  (and (every? (fn [[field value]] (= value (get node field))) (:where selection))
       (case (:op selection)
         :nodes true
         :measurements true
         :children (= (:parent node) (:anchor selection))
         :window (in-window? node (:interval selection)))))
(m/=> selected? [:=> [:cat ic/Row ic/Selection] :boolean])

(defn- cursor
  "Bind scan position to the complete artifact and selection, excluding adjustable budgets."
  [context selection-digest offset]
  {:version 1 :artifact (:artifact context) :selection selection-digest :offset offset})
(m/=> cursor [:=> [:cat ic/Prepared c/Digest [:int {:min 0 :max 1000000}]] ic/Cursor])

(defn- result
  "Construct a page; a stopped scan never claims an exact total of all matches."
  [context selection-digest offset rows visited stop]
  {:artifact (:artifact context) :rows rows :visited visited :stop stop
   :next (when-not (= :complete stop) (cursor context selection-digest offset))})
(m/=> result
      [:=> [:cat ic/Prepared c/Digest [:int {:min 0 :max 1000000}]
            [:vector {:max 1000} ic/Row] [:int {:min 0 :max 128000}] ic/StopReason] ic/Page])

(defn- starting-offset
  "Refuse foreign/stale continuations and offsets beyond the actual node inventory."
  [context request selection-digest]
  (if-let [continuation (:cursor request)]
    (do
      (when (or (not= (:artifact context) (:artifact continuation))
                (not= selection-digest (:selection continuation))
                (> (:offset continuation) (count (candidates context (:select request)))))
        (throw (ex-info "Continuation does not belong to this query" {:code :invalid-cursor})))
      (:offset continuation))
    0))
(m/=> starting-offset [:=> [:cat ic/Prepared ic/Request c/Digest] [:int {:min 0 :max 1000000}]])

(defn- scan
  "Bound every candidate visit and row encoding, reserving worst-case page metadata."
  [context request selection-digest offset]
  (let [{:keys [visits rows] max-bytes :bytes} (:budget request)
        nodes (candidates context (:select request))
        overhead (canonical/utf8-size
                  (canonical/encode (result context selection-digest 1000000 [] visits :visit-limit) 1048576))]
    (loop [position offset visited 0 found [] used overhead]
      (cond
        (= position (count nodes)) (result context selection-digest position found visited :complete)
        (= visited visits) (result context selection-digest position found visited :visit-limit)
        (= (count found) rows) (result context selection-digest position found visited :row-limit)
        :else
        (let [node (nth nodes position)]
          (if-not (selected? node (:select request))
            (recur (inc position) (inc visited) found used)
            (let [row-size (canonical/utf8-size (canonical/encode node 65536))
                  next-size (+ used row-size (if (seq found) 1 0))]
              (if (> next-size max-bytes)
                (result context selection-digest position found (inc visited) :byte-limit)
                (recur (inc position) (inc visited) (conj found node) next-size)))))))))
(m/=> scan [:=> [:cat ic/Prepared ic/Request c/Digest [:int {:min 0 :max 1000000}]] ic/Page])

(defn page
  "Drill one immutable Prepared graph with a closed Request; return a Page of nodes or measurements.
   Registered selectors are :nodes, :children, :window and :measurements, with equality-only filters.
   :window uses half-open run-relative nanosecond bounds; overlap identifies coincidence, not causality.
   :budget independently limits candidate visits, returned rows and the entire encoded response.
   :stop and :next distinguish completion from truncation; no exact total is inferred from a page.
   Resume with the same selection and returned cursor; budgets may change, so a byte-stopped row
   can be retried with more space. Foreign/stale cursors, reversed windows and unknown anchors
   throw named closed failures. Neither selector nor cursor text is evaluated as code."
  [context request]
  (when-not (m/validate request-schema request)
    (throw (ex-info "Invalid drill request" {:code :invalid-query})))
  (let [selection (:select request)
        interval (:interval selection)
        selection-digest (canonical/sha256 (canonical/encode selection 65536))]
    (when (and interval (not (d/before-or-equal? (:start-ns interval) (:end-ns interval))))
      (throw (ex-info "Reversed query window" {:code :invalid-query})))
    (when (and (= :children (:op selection)) (:anchor selection)
               (not (contains? (:node-ids context) (:anchor selection))))
      (throw (ex-info "Unknown parent anchor" {:code :missing-anchor})))
    (let [offset (starting-offset context request selection-digest)
          answer (scan context request selection-digest offset)]
      ;; This asserts the whole response budget, including its continuation.
      (canonical/encode answer (get-in request [:budget :bytes]))
      answer)))
(m/=> page [:=> [:cat ic/Prepared ic/Request] ic/Page])
