(ns gate.process-graph
  "Join clocked command supervision with a reconciled coordinator graph, without invented test spans."
  (:require [gate.batch-graph :as batch]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as d]
            [gate.graph :as graph]
            [gate.run-contract :as r]
            [gate.verdict :as verdict]
            [malli.core :as m]))

(def Captures [:vector {:max 10000} r/ProcessCapture])
(def Failure
  [:map {:closed true} [:code [:enum :process-capture-binding :process-capture-interval
                               :process-capture-verdict :process-capture-budget]]
   [:subject [:maybe c/Id]]])
(def CaptureIndex [:map-of {:max 10000} c/Id r/ProcessCapture])

(defn- refuse!
  "Name the violated capture clause without echoing commands, environment or log contents."
  [code subject]
  (throw (ex-info "Process graph projection refused" {:code code :subject subject})))
(m/=> refuse! [:=> [:cat (second (nth Failure 2)) [:maybe c/Id]] :nil])

(defn- interval
  "The supervision interval includes launch, output handling and cleanup in its owning JVM domain."
  [capture]
  (let [process (:process capture) start (:offset-ns process)]
    {:start-ns start :end-ns (d/add start (get-in process [:observation :elapsed-ns]))}))
(m/=> interval [:=> [:cat r/ProcessCapture] c/Interval])

(defn- validate-capture!
  "Bind actual argv, cwd, identity, clock, interval and separately supplied work evidence to dispatch."
  [options gate dispatch observed-batch capture]
  (let [process (:process capture) observation (:observation process)
        span (interval capture) adapter (:adapter-interval dispatch)]
    (when (or (nil? gate) (nil? adapter) (= :cached (:outcome dispatch))
              (not= (:run options) (:run capture))
              (not= (:source-digest options) (:source-digest capture))
              (not= (:label gate) (:label capture))
              (not= (:command gate) (:command capture)) (not= (:cwd gate) (:cwd capture))
              (not= (:clock observed-batch) (:clock process)))
      (refuse! :process-capture-binding (:gate capture)))
    (when-not (and (d/before-or-equal? (:start-ns adapter) (:start-ns span))
                   (d/before-or-equal? (:end-ns span) (:end-ns adapter)))
      (refuse! :process-capture-interval (:gate capture)))
    (when-not (= (:work dispatch) (verdict/process-result gate observation (:coverage capture)))
      (refuse! :process-capture-verdict (:gate capture))))
  nil)
(m/=> validate-capture!
      [:=> [:cat batch/Options [:maybe r/Gate] [:maybe r/Dispatch] r/Batch r/ProcessCapture] :nil])

(defn- capture-source
  "Keep one content identity for each raw command observation, including bounded log identity."
  [capture]
  {:id (batch/identifier "process-source" (:gate capture) (:gate capture))
   :kind :capture :label "Command supervision; descendants sampled, no containment"
   :digest (canonical/sha256 (canonical/encode capture 67108864))})
(m/=> capture-source [:=> [:cat r/ProcessCapture] c/Source])

(defn- gate-node
  "Only a launched process gets a supervision span; failed launch remains an explicit decision."
  [node capture dispatch]
  (let [observation (get-in capture [:process :observation])
        base (assoc (select-keys node [:id :key :label :kind])
                    :source (:id (capture-source capture)))]
    (if (:pid observation)
      (assoc base :parent (batch/identifier "adapter" (:gate capture) (:gate capture))
             :record :execution :attempt 1 :outcome (:outcome dispatch) :interval (interval capture))
      (assoc base :parent "run" :record :decision :at-ns (:at-ns dispatch)
             :outcome (if (= :cancelled (:outcome dispatch)) :cancelled :refused)
             :reason (if (= :cancelled (:outcome dispatch))
                       {:code :cancellation-requested :detail "Cancelled before process launch"}
                       {:code :unsupported :detail (str "No process launched: " (name (:status observation)))})))))
(m/=> gate-node [:=> [:cat c/Node r/ProcessCapture r/Dispatch] c/Node])

(defn- rephase
  "Retain dependency identity and evidence while binding endpoints to actual execution or decision records."
  [nodes edge]
  (reduce (fn [result [side phase]]
            (assoc-in result [side :phase]
                      (if (= :execution (:record (get nodes (get-in edge [side :node])))) phase :decision)))
          edge [[:from :finish] [:to :start]]))
(m/=> rephase [:=> [:cat c/NodeIndex c/Edge] c/Edge])

(defn- complete?
  "Require all dispatched commands to have observations and known stopped observed handles.
   Completeness covers the declared command scope, never unobserved descendants or internal tests."
  [captures dispatch]
  (if (or (nil? (:adapter-interval dispatch)) (= :cached (:outcome dispatch))) true
      (when-let [capture (get captures (:gate dispatch))]
        (let [observation (get-in capture [:process :observation])]
          (boolean (and (:observed-processes-stopped? observation)
                        (not= :io-failed (:status observation))
                        (not (get-in observation [:log :truncated?]))))))))
(m/=> complete? [:=> [:cat CaptureIndex r/Dispatch] [:maybe :boolean]])

(defn project
  "Join actual subprocess supervision with the complete declared coordinator roster.
   Reuse the coordinator projection's coverage, selection, dependency and clock checks. Captures
   must match their actual declarations and coarse verdicts. Missing captures remain refusals and
   make acquisition incomplete; cached gates remain instantaneous historical decisions. Commands
   with nonzero exit retain their real span and failure. Launch refusals have no fabricated span.

   The graph contains invocation supervision, not internal tests or a complete descendant forest.
   No test counts, CPU use or instruction counts are inferred. Raw captures retain process status,
   exit, pid, bounded log identity and cleanup evidence, bound by each graph source digest. Keep
   them alongside the graph. Callers still own truthful acquisition, immutable loaded code and
   independently obtained coverage; this pure projector cannot establish isolation or read sets."
  [options gates roots observed-batch captures]
  (let [base (batch/project options gates roots observed-batch [])
        by-gate (into {} (map (juxt :id identity) gates))
        dispatches (into {} (map (juxt :gate identity) (:dispatches observed-batch)))
        by-capture (into {} (map (juxt :gate identity) captures))]
    (when (> (+ 2 (count captures)) 4096) (refuse! :process-capture-budget nil))
    (when-not (= (count captures) (count by-capture)) (refuse! :process-capture-binding nil))
    (doseq [capture captures]
      (validate-capture! options (get by-gate (:gate capture)) (get dispatches (:gate capture)) observed-batch capture))
    (let [nodes (mapv (fn [node]
                        (if-let [capture (and (= :gate (:kind node)) (get by-capture (:key node)))]
                          (gate-node node capture (get dispatches (:gate capture))) node)) (:nodes base))
          index (into {} (map (juxt :id identity) nodes))
          value (-> base
                    (assoc :nodes nodes :edges (mapv #(rephase index %) (:edges base)))
                    (update :sources into (mapv capture-source captures))
                    (assoc-in [:run :complete?] (every? #(complete? by-capture %) (:dispatches observed-batch))))]
      (canonical/normalize-graph (graph/require-valid! value)))))
(m/=> project [:=> [:cat batch/Options r/Gates batch/Roots r/Batch Captures] c/Graph])
