(ns gate.diff
  "Bounded task-observation comparison; capture differences are not performance-regression verdicts."
  (:require [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as d]
            [gate.inspection-contract :as ic]
            [malli.core :as m]))

(def Offset [:int {:min 0 :max 256000}])
(def Nodes [:vector {:max 128000} c/Node])
(def Profiles [:vector {:max 256000} ic/TaskProfile])
(def Step
  [:map {:closed true} [:row ic/DiffRow] [:before-offset Offset] [:after-offset Offset]])
(def ^:private request-schema (m/schema ic/DiffRequest))

(defn- identity-of
  "Task keys must be stable and appropriately scoped by their producer."
  [node]
  (select-keys node [:kind :key]))
(m/=> identity-of [:=> [:cat c/Node] ic/TaskIdentity])

(defn- identity-order
  "Order by kind name and semantic key, identically on both runtimes."
  [a b]
  (compare [(name (:kind a)) (:key a)] [(name (:kind b)) (:key b)]))
(m/=> identity-order [:=> [:cat ic/TaskIdentity ic/TaskIdentity] :int])

(defn- semantic-observation
  "Drop capture-local identity and clock placement, retaining task state and immediate parent semantics."
  [nodes node]
  (let [parent (get nodes (:parent node))
        common {:label (:label node)
                :parent (when parent (assoc (identity-of parent) :attempt (:attempt parent)))}]
    (merge common
           (if (= :execution (:record node))
             (select-keys node [:record :attempt :outcome])
             (select-keys node [:record :outcome :reason])))))
(m/=> semantic-observation [:=> [:cat c/NodeIndex c/Node] ic/TaskSemantics])

(defn- duration-observation
  "Retain exact elapsed duration or an unfinished marker; decisions are never supplied here."
  [node]
  (let [{:keys [start-ns end-ns]} (:interval node)]
    {:attempt (:attempt node) :duration-ns (when end-ns (d/subtract end-ns start-ns))}))
(m/=> duration-observation [:=> [:cat c/Execution] ic/TaskDuration])

(defn- observation-digests
  "Keep each invocation's state associated with its duration without guessing cross-run occurrence matches."
  [index node]
  [(canonical/sha256 (canonical/encode (semantic-observation index node) 32768))
   (when (= :execution (:record node))
     (canonical/sha256 (canonical/encode (duration-observation node) 1024)))])
(m/=> observation-digests [:=> [:cat c/NodeIndex c/Node] [:tuple c/Digest [:maybe c/Digest]]])

(defn- fingerprint
  "Hash a sorted multiset of member digests, preserving duplicate observations."
  [domain members]
  (canonical/sha256
   (canonical/encode {:version 1 :domain domain :members (vec (sort members))} 16777216)))
(m/=> fingerprint
      [:=> [:cat [:enum :task-semantics :task-durations :task-associations]
            [:vector {:max 128000} c/Digest]] c/Digest])

(def ^:private empty-semantics (fingerprint :task-semantics []))
(def ^:private empty-durations (fingerprint :task-durations []))
(def ^:private empty-association (fingerprint :task-associations []))
(def ^:private empty-outcomes
  (zipmap (concat (rest c/ExecutionOutcome) (rest c/DecisionOutcome)) (repeat 0)))

(defn- profile
  "Summarize one identity without inventing pairings between repeated occurrences or retries."
  [task-identity nodes declared? index]
  (let [digests (mapv #(observation-digests index %) nodes)
        semantics (mapv first digests)
        durations (into [] (keep second) digests)
        associations (into [] (keep (fn [[semantics duration]]
                                      (when duration
                                        (canonical/sha256 (canonical/encode
                                                           {:semantics semantics :duration duration} 1024))))) digests)]
    {:identity task-identity :declared? declared? :observations (count nodes)
     :outcomes (reduce (fn [counts node] (update counts (:outcome node) inc)) empty-outcomes nodes)
     :semantics (if (seq semantics) (fingerprint :task-semantics semantics) empty-semantics)
     :durations (if (seq durations) (fingerprint :task-durations durations) empty-durations)
     :association (if (seq associations) (fingerprint :task-associations associations) empty-association)
     :examples (vec (take 4 (sort (map :id nodes))))}))
(m/=> profile [:=> [:cat ic/TaskIdentity Nodes [:maybe :boolean] c/NodeIndex] ic/TaskProfile])

(defn- profiles
  "Prepare the union of observed identities and declared gates, including expected-but-unobserved work."
  [context]
  (let [graph (:graph context) nodes (:nodes graph)
        index (into {} (map (juxt :id identity)) nodes)
        groups (group-by identity-of nodes)
        expected (set (get-in graph [:run :expected-keys]))
        identities (into (set (keys groups)) (map (fn [task-key] {:kind :gate :key task-key})) expected)]
    (mapv (fn [task]
            (profile task (get groups task [])
                     (when (= :gate (:kind task)) (contains? expected (:key task))) index))
          (sort identity-order identities))))
(m/=> profiles [:=> [:cat ic/Prepared] Profiles])

(defn prepare
  "Build immutable comparison profiles once from two validated query contexts; never accept this context from input."
  [before after]
  {:before (:artifact before) :after (:artifact after)
   :before-complete? (get-in before [:graph :run :complete?])
   :after-complete? (get-in after [:graph :run :complete?])
   :before-tasks (profiles before) :after-tasks (profiles after)})
(m/=> prepare [:=> [:cat ic/Prepared ic/Prepared] ic/DiffPrepared])

(defn- row
  "Compare profiles; one-sided presence never claims deletion or a cache hit."
  [before after]
  (when-not (or before after)
    (throw (ex-info "Comparison key has no profiles" {:code :invalid-comparison})))
  (let [dimensions (if (and before after)
                     (into [] (keep (fn [[field dimension]]
                                      (when (not= (get before field) (get after field)) dimension)))
                           [[:semantics :semantics] [:durations :durations]])
                     [])
        dimensions (if (and before after (empty? dimensions)
                            (not= (:association before) (:association after))) [:association] dimensions)]
    {:identity (:identity (or before after)) :before before :after after :dimensions dimensions
     :change (cond (nil? before) :after-only (nil? after) :before-only
                   (seq dimensions) :changed :else :unchanged)}))
(m/=> row [:=> [:cat [:maybe ic/TaskProfile] [:maybe ic/TaskProfile]] ic/DiffRow])

(defn- advance
  "Consume one key from a sorted merge, consuming both sides only when their identities agree."
  [context before-offset after-offset]
  (let [before (get (:before-tasks context) before-offset)
        after (get (:after-tasks context) after-offset)
        order (cond (nil? before) 1 (nil? after) -1
                    :else (identity-order (:identity before) (:identity after)))]
    (cond
      (neg? order) {:row (row before nil) :before-offset (inc before-offset) :after-offset after-offset}
      (pos? order) {:row (row nil after) :before-offset before-offset :after-offset (inc after-offset)}
      :else {:row (row before after) :before-offset (inc before-offset) :after-offset (inc after-offset)})))
(m/=> advance [:=> [:cat ic/DiffPrepared Offset Offset] Step])

(defn- selected?
  "Filter profiles with registered identity fields and an explicit changed-only choice."
  [observation selection]
  (and (or (not (:changed-only? selection)) (not= :unchanged (:change observation)))
       (every? (fn [[field value]] (= value (get (:identity observation) field))) (:where selection))))
(m/=> selected? [:=> [:cat ic/DiffRow ic/DiffSelection] :boolean])

(defn- result
  "Retain both artifact identities and capture completeness in every comparison page."
  [context selection-digest before-offset after-offset rows visited stop]
  (merge (select-keys context [:before :after :before-complete? :after-complete?])
         {:scope :task-observations :rows rows :visited visited :stop stop
          :next (when-not (= :complete stop)
                  {:version 1 :before (:before context) :after (:after context)
                   :selection selection-digest :before-offset before-offset :after-offset after-offset})}))
(m/=> result
      [:=> [:cat ic/DiffPrepared c/Digest Offset Offset [:vector {:max 1000} ic/DiffRow]
            [:int {:min 0 :max 128000}] ic/StopReason] ic/DiffPage])

(defn- frontier-valid?
  "A cursor must lie between complete merged keys, never halfway through a shared identity."
  [context before-offset after-offset]
  (let [before (:before-tasks context) after (:after-tasks context)
        previous-before (get before (dec before-offset)) next-before (get before before-offset)
        previous-after (get after (dec after-offset)) next-after (get after after-offset)]
    (and (<= before-offset (count before)) (<= after-offset (count after))
         (or (nil? previous-before) (nil? next-after)
             (neg? (identity-order (:identity previous-before) (:identity next-after))))
         (or (nil? previous-after) (nil? next-before)
             (neg? (identity-order (:identity previous-after) (:identity next-before)))))))
(m/=> frontier-valid? [:=> [:cat ic/DiffPrepared Offset Offset] :boolean])

(defn- starting-offsets
  "Bind continuation direction, both immutable artifacts, selection and merge frontier."
  [context request selection-digest]
  (if-let [cursor (:cursor request)]
    (let [before (:before-offset cursor) after (:after-offset cursor)]
      (when-not (and (= (:before context) (:before cursor)) (= (:after context) (:after cursor))
                     (= selection-digest (:selection cursor)) (frontier-valid? context before after))
        (throw (ex-info "Continuation does not belong to this comparison" {:code :invalid-cursor})))
      [before after])
    [0 0]))
(m/=> starting-offsets [:=> [:cat ic/DiffPrepared ic/DiffRequest c/Digest] [:tuple Offset Offset]])

(defn- scan
  "Count filtered keys as visits and reserve the complete page envelope before appending rows."
  [context request selection-digest before-offset after-offset]
  (let [{:keys [visits rows] max-bytes :bytes} (:budget request)
        overhead (canonical/utf8-size
                  (canonical/encode (result context selection-digest 256000 256000 [] visits :visit-limit) 1048576))]
    (loop [before before-offset after after-offset visited 0 found [] used overhead]
      (cond
        (and (= before (count (:before-tasks context))) (= after (count (:after-tasks context))))
        (result context selection-digest before after found visited :complete)
        (= visited visits) (result context selection-digest before after found visited :visit-limit)
        (= (count found) rows) (result context selection-digest before after found visited :row-limit)
        :else
        (let [step (advance context before after) observation (:row step)]
          (if-not (selected? observation (:select request))
            (recur (:before-offset step) (:after-offset step) (inc visited) found used)
            (let [row-size (canonical/utf8-size (canonical/encode observation 32768))
                  next-size (+ used row-size (if (seq found) 1 0))]
              (if (> next-size max-bytes)
                (result context selection-digest before after found (inc visited) :byte-limit)
                (recur (:before-offset step) (:after-offset step) (inc visited)
                       (conj found observation) next-size)))))))))
(m/=> scan [:=> [:cat ic/DiffPrepared ic/DiffRequest c/Digest Offset Offset] ic/DiffPage])

(defn page
  "Compare task profiles from a DiffPrepared context with a closed DiffRequest, returning a DiffPage.
   Rows group stable kind/key identities and retain repetition; changed dimensions distinguish task
   semantics, duration distributions and their association. Neither profile equality nor changed
   duration proves whole-graph equality, comparable machine load or a performance regression.
   Each page retains both artifact identities and capture-completeness flags. Row/visit/whole-response
   byte budgets bound the merge scan; :next resumes the same comparison and selection. A byte-stopped
   key can be retried with a larger budget. Invalid/stale/foreign continuations and malformed requests
   throw named closed failures. Resource/dependency/measurement comparison is outside this operation."
  [context request]
  (when-not (m/validate request-schema request)
    (throw (ex-info "Invalid comparison request" {:code :invalid-comparison})))
  (let [selection-digest (canonical/sha256 (canonical/encode (:select request) 65536))
        [before after] (starting-offsets context request selection-digest)
        answer (scan context request selection-digest before after)]
    (canonical/encode answer (get-in request [:budget :bytes]))
    answer))
(m/=> page [:=> [:cat ic/DiffPrepared ic/DiffRequest] ic/DiffPage])
