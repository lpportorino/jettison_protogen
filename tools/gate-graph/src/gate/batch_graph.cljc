(ns gate.batch-graph
  "Portable coordinator/test projection with exact clock anchors and declaration reconciliation."
  (:require [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as d]
            [gate.graph :as graph]
            [gate.plan :as plan]
            [gate.run-contract :as r]
            [gate.test-graph :as tests]
            [gate.verdict :as verdict]
            [malli.core :as m]))

(def Options [:map {:closed true} [:run c/Id] [:key c/Id] [:label c/Label] [:source-digest c/Digest]])
(def Captures [:vector {:max 10000} r/ClockedTests])
(def Roots [:vector {:max 10000} c/Id])
(def Failure
  [:map {:closed true} [:code [:enum :batch-roster :batch-dispatch :batch-status :batch-evidence-budget
                               :capture-binding :capture-coverage :capture-interval]]
   [:subject [:maybe c/Id]]])
(def DispatchIndex [:map-of {:max 10000} c/Id r/Dispatch])
(def CaptureIndex [:map-of {:max 10000} c/Id r/ClockedTests])

(defn- refuse!
  "Name the failed projection clause without copying commands, paths or rejected observations."
  [code subject]
  (throw (ex-info "Batch graph projection refused" {:code code :subject subject})))
(m/=> refuse! [:=> [:cat (second (nth Failure 2)) [:maybe c/Id]] :nil])

(defn identifier
  "Derive bounded namespaced identities from a collection kind, gate identity and local identity."
  [kind gate local]
  (str kind "/" (canonical/sha256 (str (count gate) ":" gate (count local) ":" local))))
(m/=> identifier [:=> [:cat [:string {:min 1 :max 16}] c/Id c/Id] c/Id])

(defn- validate-dispatch!
  "Reconcile actual dispatch evidence with selection, outcomes, coverage and prerequisite timing."
  [gate dispatch selected index duration]
  (let [id (:id gate) work (:work dispatch) interval (:adapter-interval dispatch)
        hard (filter #(contains? #{:requires :produces} (:relation %)) (:dependencies gate))
        unsuccessful (set (for [dependency hard
                                :when (not (contains? #{:passed :cached} (:outcome (get index (:gate dependency)))))]
                            (:gate dependency)))]
    (when (or (not (d/before-or-equal? (:at-ns dispatch) duration))
              (not= (contains? selected id) (not= :deselected (:outcome dispatch)))
              (not= (some? work) (some? interval))
              (if work
                (or (not= work (verdict/judge gate work))
                    (not= (:outcome dispatch) (:outcome work))
                    (not= (:at-ns dispatch) (:end-ns interval))
                    (not (d/before-or-equal? (:start-ns interval) (:end-ns interval))))
                (not (contains? #{:blocked :deselected :cancelled} (:outcome dispatch))))
              (if (= :blocked (:outcome dispatch))
                (or (empty? (:blocked-by dispatch))
                    (some #(not (contains? unsuccessful %)) (:blocked-by dispatch)))
                (seq (:blocked-by dispatch))))
      (refuse! :batch-dispatch id))
    (when (and work (contains? #{:passed :cached} (:outcome work))
               (not (and (= (get-in gate [:coverage :expected])
                            (get-in work [:coverage :expected]) (get-in work [:coverage :observed]))
                         (>= (get-in work [:coverage :count] -1) (get-in gate [:coverage :minimum])))))
      (refuse! :capture-coverage id))
    (when interval
      (doseq [{parent :gate relation :relation} (:dependencies gate) :when (not= :invalidates relation)]
        (when (or (not (d/before-or-equal? (:at-ns (get index parent)) (:start-ns interval)))
                  (and (contains? #{:requires :produces} relation)
                       (not (contains? #{:passed :cached} (:outcome (get index parent))))))
          (refuse! :batch-dispatch id)))))
  nil)
(m/=> validate-dispatch! [:=> [:cat r/Gate r/Dispatch [:set {:max 10000} c/Id] DispatchIndex c/Natural] :nil])

(defn- validate-capture!
  "Require a capture's run/gate/label/clock and coverage to agree with its owning real dispatch."
  [options gate dispatch batch capture]
  (let [observation (:observation capture) interval (:adapter-interval dispatch)
        start (:offset-ns capture) end (d/add start (:duration-ns observation))]
    (when (or (nil? interval) (contains? #{:cached :deselected :blocked :cancelled} (:outcome dispatch))
              (not= (:clock batch) (:clock capture)) (not= (:run options) (:run observation))
              (not= (:id gate) (:gate observation)) (not= (:label gate) (:label observation))
              (not= (:outcome dispatch) (:status observation))
              (and (not= (:source-digest options) (:source-digest observation))
                   (not (some #{:source-changed} (:problems observation)))))
      (refuse! :capture-binding (:id gate)))
    (when-not (= (get-in dispatch [:work :coverage]) (:coverage observation))
      (refuse! :capture-coverage (:id gate)))
    (when-not (and (d/before-or-equal? (:start-ns interval) start)
                   (d/before-or-equal? end (:end-ns interval)))
      (refuse! :capture-interval (:id gate)))
    (tests/project observation))
  nil)
(m/=> validate-capture! [:=> [:cat Options r/Gate r/Dispatch r/Batch r/ClockedTests] :nil])

(defn- validate!
  "Recompute the declaration closure, account for every dispatch, and bound graph expansion first."
  [options gates roots batch captures]
  (let [schedule (plan/schedule gates roots)
        index (into {} (map (juxt :gate identity) (:dispatches batch)))
        declarations (into {} (map (juxt :id identity) gates))
        selected (set (:selected schedule))
        capture-ids (mapv #(get-in % [:observation :gate]) captures)
        successful? (every? #(contains? #{:passed :cached :deselected} (:outcome %)) (:dispatches batch))
        extra-nodes (reduce + (map #(let [o (:observation %) observed (set (map :key (:executions o)))]
                                      (+ (count (:executions o)) (count (:problems o))
                                         (count (remove (comp observed :key) (:inventory o))))) captures))]
    (when (or (not= schedule (:schedule batch))
              (not= (:order schedule) (mapv :gate (:dispatches batch))))
      (refuse! :batch-roster nil))
    (when (or (and (= :passed (:status batch)) (not successful?))
              (and (= :failed (:status batch)) successful?))
      (refuse! :batch-status nil))
    (when (or (> (+ 2 (* 3 (count captures))) 4096)
              (> (+ 1 (* 2 (count gates)) extra-nodes) 128000)
              (> (reduce + (map #(count (:dependencies %)) gates)) 512000)
              (> (reduce + (map #(+ (* 5 (count (get-in % [:observation :executions]))) 4) captures)) 1000000))
      (refuse! :batch-evidence-budget nil))
    (when-not (= (count capture-ids) (count (set capture-ids))) (refuse! :capture-binding nil))
    (doseq [gate gates]
      (validate-dispatch! gate (get index (:id gate)) selected index (:duration-ns batch)))
    (doseq [capture captures]
      (let [id (get-in capture [:observation :gate]) gate (get declarations id)]
        (when-not gate (refuse! :capture-binding id))
        (validate-capture! options gate (get index id) batch capture))))
  nil)
(m/=> validate! [:=> [:cat Options r/Gates Roots r/Batch Captures] :nil])

(defn- shift-interval
  "Translate local instants with exact decimal arithmetic, preserving unknown interval ends."
  [offset interval]
  {:start-ns (d/add offset (:start-ns interval))
   :end-ns (when-let [end (:end-ns interval)] (d/add offset end))})
(m/=> shift-interval [:=> [:cat c/Natural c/OpenInterval] c/OpenInterval])

(defn- capture-graph
  "Remap every local collection/reference and translate suite/test/measurement instants once."
  [capture]
  (let [observation (:observation capture) gate (:gate observation) offset (:offset-ns capture)
        local (tests/project observation)
        node-id #(if (= "suite" %) (identifier "gate" gate gate) (identifier "node" gate %))
        source-id #(identifier "source" gate %)
        resource-id #(identifier "resource" gate %)]
    {:sources (conj (mapv #(assoc % :id (source-id (:id %))) (:sources local))
                    {:id (identifier "anchor" gate gate) :kind :import :label "Same-JVM test clock anchor"
                     :digest (canonical/sha256 (canonical/encode capture 67108864))})
     :nodes (mapv (fn [node]
                    (let [node (assoc node :id (node-id (:id node)) :source (source-id (:source node))
                                      :parent (if (:parent node) (node-id (:parent node)) (identifier "adapter" gate gate)))]
                      (if (= :execution (:record node)) (update node :interval #(shift-interval offset %))
                          (update node :at-ns #(d/add offset %))))) (:nodes local))
     :resources (mapv #(assoc % :id (resource-id (:id %)) :source (source-id (:source %))
                              :parent (when (:parent %) (resource-id (:parent %)))) (:resources local))
     :measurements (mapv #(assoc % :id (identifier "measurement" gate (:id %))
                                 :node (when (:node %) (node-id (:node %))) :resource (resource-id (:resource %))
                                 :source (source-id (:source %)) :interval (shift-interval offset (:interval %))) (:measurements local))}))
(m/=> capture-graph [:=> [:cat r/ClockedTests] [:map {:closed true}
                                                [:sources [:vector {:max 3} c/Source]] [:nodes [:vector {:max 20009} c/Node]]
                                                [:resources [:vector {:max 1} c/Resource]]
                                                [:measurements [:vector {:max 50004} c/Measurement]]]])

(defn- decision-node
  "Represent cache, selection, prerequisite, cancellation or acquisition decisions without fake execution."
  [gate dispatch]
  (let [outcome (:outcome dispatch)
        [outcome code detail] (case outcome
                                :cached [:cached :cache-hit "Historical coverage reused; tests did not execute"]
                                :deselected [:deselected :unchanged "Outside the selected prerequisite closure"]
                                :blocked [:blocked :prerequisite-failed "A required prerequisite did not succeed"]
                                :cancelled [:cancelled :cancellation-requested "Cancellation without observed test execution"]
                                [:refused :missing-evidence "Adapter finished without anchored execution evidence"])]
    {:id (identifier "gate" (:id gate) (:id gate)) :key (:id gate) :label (:label gate)
     :kind :gate :source "coordinator" :parent "run" :record :decision
     :at-ns (:at-ns dispatch) :outcome outcome :reason {:code code :detail detail}}))
(m/=> decision-node [:=> [:cat r/Gate r/Dispatch] c/Decision])

(defn- adapter-node
  "Keep actual adapter time as a boundary, including lookup/publication rather than inventing command time."
  [gate dispatch]
  {:id (identifier "adapter" (:id gate) (:id gate)) :key (identifier "adapter" (:id gate) (:id gate))
   :label (:label gate)
   :kind :boundary :source "coordinator" :parent "run" :record :execution :attempt 1
   :outcome (if (= :cached (:outcome dispatch)) :passed (:outcome dispatch))
   :interval (:adapter-interval dispatch)})
(m/=> adapter-node [:=> [:cat r/Gate r/Dispatch] c/Execution])

(defn project
  "Merge a reconciled coordinator Batch and same-JVM clocked test captures into one canonical graph.
   Adapter boundaries, actual tests and instantaneous decisions remain separate. Every gate is
   accounted for; missing dispatched execution evidence produces a refusal and an incomplete graph.
   Capture completeness is independent of a passing verdict. Definitions retain every dependency,
   including invalidation-only declared edges. Only actually dispatched prerequisite relationships
   assert observed causation. Clock IDs, offset containment, identities and coverage are checked;
   the caller still owns truthful acquisition and immutable loaded code. Independent capture sources
   remain distinct, so cross-source or unproven overlapping totals refuse under the existing algebra.
   Expansion is conservatively bounded before copying captures; exact final graph invariants apply."
  [options gates roots batch captures]
  (validate! options gates roots batch captures)
  (let [by-id (into {} (map (juxt :gate identity) (:dispatches batch)))
        by-capture (into {} (map #(vector (get-in % [:observation :gate]) %) captures))
        projected (mapv capture-graph captures)
        nodes (into [{:id "run" :key (:key options) :label (:label options) :parent nil :source "coordinator"
                      :kind :chain :record :execution :attempt 1 :outcome (:status batch)
                      :interval {:start-ns "0" :end-ns (:duration-ns batch)}}]
                    (concat (for [gate gates :let [dispatch (get by-id (:id gate))]
                                  :when (:adapter-interval dispatch)] (adapter-node gate dispatch))
                            (for [gate gates :when (not (contains? by-capture (:id gate)))]
                              (decision-node gate (get by-id (:id gate))))
                            (mapcat :nodes projected)))
        node-index (into {} (map (juxt :id identity) nodes))
        edges (vec (for [gate gates dependency (:dependencies gate)
                         :let [from (get node-index (identifier "gate" (:gate dependency) (:gate dependency)))
                               to (get node-index (identifier "gate" (:id gate) (:id gate)))
                               dispatched? (some? (:adapter-interval (get by-id (:id gate))))]]
                     {:id (identifier "dependency" (:id gate) (identifier "relation" (:gate dependency) (name (:relation dependency))))
                      :source "gate-definitions" :kind (:relation dependency)
                      :from {:node (:id from) :phase (if (= :execution (:record from)) :finish :decision)}
                      :to {:node (:id to) :phase (if (= :execution (:record to)) :start :decision)}
                      :evidence (if (and dispatched? (not= :invalidates (:relation dependency))) :observed :declared)}))
        complete? (every? (fn [dispatch]
                            (if (contains? #{:passed :failed :error} (:outcome dispatch))
                              (true? (get-in by-capture [(:gate dispatch) :observation :complete?])) true)) (:dispatches batch))
        value {:schema/version 1
               :run {:id (:run options) :source-digest (:source-digest options) :complete? complete?
                     :clock {:id (:clock batch) :origin-ns "0"} :end-ns (:duration-ns batch) :expected-keys (mapv :id gates)}
               :sources (into [{:id "coordinator" :kind :capture :label "Coordinator dispatch and decisions"
                                :digest (canonical/sha256 (canonical/encode batch 67108864))}
                               {:id "gate-definitions" :kind :declaration :label "Independent gate definitions"
                                :digest (canonical/sha256 (canonical/encode gates 67108864))}] (mapcat :sources projected))
               :nodes nodes :edges edges :resources (vec (mapcat :resources projected))
               :measurements (vec (mapcat :measurements projected))}]
    (canonical/normalize-graph (graph/require-valid! value))))
(m/=> project [:=> [:cat Options r/Gates Roots r/Batch Captures] c/Graph])
