(ns gate.view.model
  "Deterministic reversible folds and bounded evidence pages over one admitted immutable graph."
  (:require [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as d]
            [gate.graph :as graph]
            [gate.inspection-contract :as ic]
            [gate.interval :as interval]
            [gate.query :as query]
            [gate.view.contract :as vc]
            [malli.core :as m]))

(def Nodes [:vector {:max 128000} c/Node])
(def ^:private MembersSchema (m/schema [:vector {:min 1 :max 256} c/Node]))
(def ^:private RequestSchema (m/schema vc/Request))
(def Prepared
  [:map {:closed true} [:query ic/Prepared] [:nodes c/NodeIndex]
   [:children [:map-of {:max 128001} [:maybe c/Id] Nodes]]
   [:neighbors [:map-of {:max 128000} c/Id [:set {:max 128000} c/Id]]]])

(defn prepare
  "Admit once and retain privately. Reprepare after any artifact change; never accept this index from an agent.
   Graph admission budgets bound preparation separately from page visits/rows/bytes. No file is reread here."
  [value]
  (let [context (query/prepare value)
        nodes (:ordered-nodes context)]
    {:query context :nodes (into {} (map (juxt :id identity)) nodes)
     :children (group-by :parent nodes)
     :neighbors (reduce (fn [index edge] (let [a (get-in edge [:from :node]) b (get-in edge [:to :node])]
                                           (-> index (update a (fnil conj #{}) b) (update b (fnil conj #{}) a))))
                        {} (get-in context [:graph :edges]))}))
(m/=> prepare [:=> [:cat c/Graph] Prepared])

(defn fold
  "Summarize 1–256 exact members. Only finished execution intervals enter duration algebra.
   Decisions and unfinished executions remain counted, never assigned invented durations.
   Occupied segments preserve gaps; summed inclusive duration is not elapsed or exclusive work."
  [nodes]
  (when (or (not (m/validate MembersSchema nodes))
            (not= (count nodes) (count (set (map :id nodes)))))
    (throw (ex-info "Invalid fold members" {:code :invalid-view-request})))
  (let [members (vec (sort (map :id nodes)))
        intervals (vec (keep #(when (and (= :execution (:record %)) (graph/node-end %)) (:interval %)) nodes))]
    {:id (str "fold/" (canonical/sha256 (str/join "\n" members)))
     :members members :summary (interval/summarize intervals) :occupied (interval/union intervals)
     :outcomes (frequencies (map :outcome nodes))
     :unfinished (count (filter #(and (= :execution (:record %)) (nil? (graph/node-end %))) nodes))
     :decisions (count (filter #(= :decision (:record %)) nodes))}))
(m/=> fold [:=> [:cat [:vector {:min 1 :max 256} c/Node]] vc/Fold])

(defn overlaps?
  "Ask whether observation a intersects query window b. This is asymmetric for points:
   a point is included at the left boundary, never the right; an empty query window matches nothing.
   Both intervals must be ordered in one admitted clock domain."
  [a b]
  (when (or (pos? (d/compare (:start-ns a) (:end-ns a))) (pos? (d/compare (:start-ns b) (:end-ns b))))
    (throw (ex-info "Reversed view interval" {:code :invalid-view-request})))
  (and (neg? (d/compare (:start-ns b) (:end-ns b)))
       (neg? (d/compare (:start-ns a) (:end-ns b)))
       (if (= (:start-ns a) (:end-ns a))
         (d/before-or-equal? (:start-ns b) (:start-ns a))
         (pos? (d/compare (:end-ns a) (:start-ns b))))))
(m/=> overlaps? [:=> [:cat c/Interval c/Interval] :boolean])

(defn- matches?
  "Apply a closed selector without evaluating text. Edge pages include internal and boundary endpoints."
  [row selection neighbors]
  (let [members (set (:members selection))]
    (case (:op selection)
      :members (contains? members (:id row))
      :edges (or (contains? members (get-in row [:from :node])) (contains? members (get-in row [:to :node])))
      :neighborhood (boolean (some #(contains? (get neighbors % #{}) (:id row)) members))
      :correlation (overlaps? (:interval row) (:interval selection))
      :search (str/includes? (str/lower-case (str (:id row) " " (:key row) " " (:label row) " " (name (:outcome row))))
                             (str/lower-case (:text selection))))))
(m/=> matches? [:=> [:cat vc/Row vc/Selection [:map-of {:max 128000} c/Id [:set {:max 128000} c/Id]]] :boolean])

(defn- candidates
  "Select a stable inventory; scans count all candidates including nonmatches."
  [context selection]
  (case (:op selection)
    :edges (get-in context [:query :graph :edges])
    :neighborhood (get-in context [:query :ordered-nodes])
    :correlation (get-in context [:query :graph :measurements])
    (get-in context [:query :ordered-nodes])))
(m/=> candidates [:=> [:cat Prepared vc/Selection] [:vector {:max 1000000} vc/Row]])

(defn- response
  "Bind continuations to exact graph and selection, independent of adjustable budgets."
  [artifact selection offset rows visited stop]
  {:artifact artifact :rows rows :visited visited :stop stop
   :next (when-not (= stop :complete) {:version 1 :artifact artifact :selection selection :offset offset})})
(m/=> response [:=> [:cat c/Digest c/Digest [:int {:min 0 :max 1000000}] [:vector {:max 1000} vc/Row]
                     [:int {:min 0 :max 128000}] ic/StopReason] vc/Page])

(defn page
  "Return bounded canonical node, typed-edge or coincident-measurement evidence.
   Members/edges/neighborhood require exact member IDs; neighborhood returns immediate endpoint nodes,
   excluding no kinds and making no transitive or causal claim. Search requires text; correlation requires
   a half-open run-clock interval. Correlation reports observations, never attribution or interpolation.
   Every candidate spends a visit; whole response spends bytes. Resume identical selection with :next.
   Unknown IDs, irrelevant fields, malformed requests and stale cursors have closed view failures."
  [context request]
  (when-not (m/validate RequestSchema request)
    (throw (ex-info "Invalid view request" {:code :invalid-view-request})))
  (let [{:keys [select budget cursor]} request
        required (case (:op select) (:members :edges :neighborhood) #{:op :members} :search #{:op :text} :correlation #{:op :interval})]
    (when (or (not= required (set (keys select)))
              (and (:members select) (not= (count (:members select)) (count (set (:members select)))))
              (and (:interval select) (pos? (d/compare (get-in select [:interval :start-ns]) (get-in select [:interval :end-ns])))))
      (throw (ex-info "Invalid view selector" {:code :invalid-view-request})))
    (when (some #(not (contains? (:nodes context) %)) (:members select))
      (throw (ex-info "Unknown view member" {:code :missing-view-member})))
    (let [artifact (get-in context [:query :artifact])
          digest (canonical/sha256 (canonical/encode select 65536))
          source (candidates context select) offset (or (:offset cursor) 0)
          overhead (canonical/utf8-size (canonical/encode (response artifact digest 1000000 [] (:visits budget) :visit-limit) 1048576))]
      (when (and cursor (or (not= artifact (:artifact cursor)) (not= digest (:selection cursor)) (> offset (count source))))
        (throw (ex-info "Foreign view continuation" {:code :invalid-view-cursor})))
      (let [answer
            (loop [position offset visited 0 rows [] used overhead]
              (cond
                (= position (count source)) (response artifact digest position rows visited :complete)
                (= visited (:visits budget)) (response artifact digest position rows visited :visit-limit)
                (= (count rows) (:rows budget)) (response artifact digest position rows visited :row-limit)
                :else
                (let [row (nth source position)]
                  (if-not (matches? row select (:neighbors context))
                    (recur (inc position) (inc visited) rows used)
                    (let [cost (+ (canonical/utf8-size (canonical/encode row 65536)) (if (seq rows) 1 0))]
                      (if (> (+ used cost) (:bytes budget))
                        (response artifact digest position rows (inc visited) :byte-limit)
                        (recur (inc position) (inc visited) (conj rows row) (+ used cost))))))))]
        (canonical/encode answer (:bytes budget))
        answer))))
(m/=> page [:=> [:cat Prepared vc/Request] vc/Page])
