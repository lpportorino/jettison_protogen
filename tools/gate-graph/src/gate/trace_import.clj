(ns gate.trace-import
  "Pure v1 capture projection with explicit expected inventory and exact wait4 quantities."
  (:require [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as decimal]
            [gate.graph :as graph]
            [gate.trace-contract :as t]
            [gate.trace-io :as trace-io]
            [jsonista.core :as json]
            [malli.core :as m]))

(def ^:private ordered-mapper (json/object-mapper {:order-by-keys true}))
(def ^:private kinds {"chain" :chain "gate" :gate "test" :test "step" :step "boundary" :boundary})
(def ^:private outcomes {"success" :passed "failure" :failed "error" :error})

(defn- one-record
  "Resolve a required singleton by layout; duplicate paths cannot silently overwrite."
  [journal path required?]
  (let [entries (filter #(= path (:path %)) journal)]
    (when (or (> (count entries) 1) (and required? (empty? entries)))
      (trace-io/refuse! :trace-layout path []))
    (:record (first entries))))
(m/=> one-record [:=> [:cat t/Journal t/Path :boolean] [:maybe t/Record]])

(defn- relative-time
  "Subtract the declared monotonic origin without floating-point conversion."
  [header instant]
  (when (< instant (get header "started_mono_ns")) (trace-io/refuse! :trace-clock "run" []))
  (decimal/subtract (str instant) (str (get header "started_mono_ns"))))
(m/=> relative-time [:=> [:cat t/Header t/Counter] c/Natural])

(defn- span-pairs
  "Match file identity, trace identity and all start fields before accepting final records."
  [journal header]
  (let [entries (filter #(str/starts-with? (:path %) "spans/") journal)
        by-path (group-by :path entries)
        groups (group-by #(get-in % [:record "span_id"]) entries)]
    (when (empty? entries) (trace-io/refuse! :trace-empty "spans" []))
    (when (> (count groups) 127999) (trace-io/refuse! :trace-file-limit "spans" []))
    (doseq [[path matches] by-path]
      (when-not (= 1 (count matches)) (trace-io/refuse! :trace-layout path [])))
    (mapv
     (fn [[id matches]]
       (doseq [{:keys [path record]} matches]
         (let [suffix (case (get record "record") "span-start" ".start.json" "span" ".span.json" nil)]
           (when (or (nil? suffix) (not= path (str "spans/" id suffix))
                     (= id (get header "root_span_id"))
                     (not= (get record "trace_id") (get header "run_id")))
             (trace-io/refuse! :trace-identity path []))))
       (let [start (:record (first (filter #(= "span-start" (get-in % [:record "record"])) matches)))
             final (:record (first (filter #(= "span" (get-in % [:record "record"])) matches)))]
         (when-not start (trace-io/refuse! :trace-start-mismatch (str "spans/" id) []))
         (when (and final (not= (dissoc start "record")
                                (select-keys final (keys (dissoc start "record")))))
           (trace-io/refuse! :trace-start-mismatch (str "spans/" id) []))
         (or final start)))
     (sort-by key groups))))
(m/=> span-pairs [:=> [:cat t/Journal t/Header] [:vector {:min 1 :max 127999} [:or t/Start t/Span]]])

(defn- check-exit!
  "Refuse forged verdicts and inconsistent spawn-failure resource/PID claims."
  [span]
  (when (= "span" (get span "record"))
    (let [status (get-in span ["exit" "status"])
          expected (case status "spawn-failed" "error" "signaled" "failure"
                         "exited" (if (zero? (get-in span ["exit" "code"])) "success" "failure"))
          spawn-failed? (= "spawn-failed" status)]
      (when (or (not= expected (get span "verdict"))
                (not= spawn-failed? (nil? (get span "rusage")))
                (not= spawn-failed? (nil? (get-in span ["attributes" "process.pid"]))))
        (trace-io/refuse! :trace-exit (str "spans/" (get span "span_id")) [])))))
(m/=> check-exit! [:=> [:cat [:or t/Start t/Span]] :nil])

(defn- node
  "Keep capture IDs distinct from semantic names; open markers remain running occurrences."
  [header inventory span attempt]
  (let [label (get span "name") kind (get kinds (get span "kind"))
        task-key (if (= :gate kind)
                   (get inventory label)
                   (str "task/" (canonical/sha256 (str (name kind) ":" label))))]
    (when-not task-key (trace-io/refuse! :trace-inventory (str "spans/" (get span "span_id")) []))
    {:id (get span "span_id") :key task-key :label label :parent (get span "parent_span_id")
     :source "trace" :kind kind :record :execution :attempt attempt
     :outcome (get outcomes (get span "verdict") :running)
     :interval {:start-ns (relative-time header (get span "start_mono_ns"))
                :end-ns (when-let [end (get span "end_mono_ns")] (relative-time header end))}}))
(m/=> node [:=> [:cat t/Header t/InventoryIndex [:or t/Start t/Span] c/Attempt] c/Execution])

(defn- measurements
  "wait4 covers the child and waited descendants; retain inclusive accounting and individual peaks."
  [header span]
  (if-let [usage (get span "rusage")]
    (mapv (fn [[field quantity multiplier form]]
            {:id (str (get span "span_id") "/" (name quantity))
             :node (get span "span_id") :resource (str "work/" (get span "span_id")) :source "trace"
             :quantity quantity :form form :accounting :inclusive
             :interval {:start-ns (relative-time header (get span "start_mono_ns"))
                        :end-ns (relative-time header (get span "end_mono_ns"))}
             :status :measured :value (str (*' multiplier (get usage field))) :reason nil :method :wait4})
          [["user_us" :cpu-user-ns 1000 :delta]
           ["sys_us" :cpu-system-ns 1000 :delta]
           ["maxrss_kib" :rss-peak-bytes 1024 :peak]])
    []))
(m/=> measurements [:=> [:cat t/Header [:or t/Start t/Span]] [:vector {:max 3} c/Measurement]])

(defn project
  "Project a quiescent v1 journal. Inventory comes from the caller, never inferred from observations.
   The original journal remains authoritative for exit details, host/toolchain facts and other rusage."
  [journal inventory]
  (when (or (not= (count inventory) (count (set (map :name inventory))))
            (not= (count inventory) (count (set (map :key inventory)))))
    (trace-io/refuse! :trace-inventory "inventory" []))
  (let [header (one-record journal "run.json" true)
        end (one-record journal "end.json" false)]
    (when-not (and (m/validate t/Header header) (or (nil? end) (m/validate t/End end)))
      (trace-io/refuse! :trace-layout "run" []))
    (when (and end (not= (get header "run_id") (get end "run_id")))
      (trace-io/refuse! :trace-identity "end.json" []))
    (doseq [{:keys [path]} journal]
      (when-not (or (contains? #{"run.json" "end.json"} path)
                    (re-matches #"spans/[0-9a-f]{16}\.(?:start|span)\.json" path))
        (trace-io/refuse! :trace-layout path [])))
    (let [inventory-index (into {} (map (juxt :name :key) inventory))
          spans (span-pairs journal header)
          _ (doseq [span spans] (check-exit! span))
          ordered (sort-by (juxt #(get % "start_mono_ns") #(get % "span_id")) spans)
          [_ nodes] (reduce (fn [[attempts nodes] span]
                              (let [task-identity [(get span "kind") (get span "name")]
                                    attempt (inc (get attempts task-identity 0))]
                                [(assoc attempts task-identity attempt)
                                 (conj nodes (node header inventory-index span attempt))])) [{} []] ordered)
          complete? (boolean (and end (every? #(= "span" (get % "record")) spans)))
          end-time (when end (relative-time header (get end "ended_mono_ns")))
          root (get header "root_span_id")
          capture-digest (canonical/sha256 (pr-str (mapv (juxt :path :digest) (sort-by :path journal))))
          declaration-digest (canonical/sha256 (pr-str (mapv (juxt :name :key) (sort-by :name inventory))))
          result {:schema/version 1
                  :run {:id (get header "run_id")
                        :source-digest (canonical/sha256 (json/write-value-as-string (get header "vcs") ordered-mapper))
                        :complete? complete? :clock {:id (str "trace/" (get header "run_id"))
                                                     :origin-ns (str (get header "started_mono_ns"))}
                        :end-ns (when complete? end-time) :expected-keys (mapv :key inventory)}
                  :sources [{:id "trace" :kind :capture :digest capture-digest :label "gate-trace v1 journal"}
                            {:id "inventory" :kind :declaration :digest declaration-digest :label "Expected gate inventory"}]
                  :nodes (into [{:id root :key (str "chain/" (get header "entrypoint"))
                                 :label (get header "entrypoint") :parent nil :source "trace" :kind :chain
                                 :record :execution :attempt 1
                                 :outcome (cond (not complete?) :running
                                                (some #(not= :passed (:outcome %)) nodes) :failed :else :passed)
                                 :interval {:start-ns "0" :end-ns (when complete? end-time)}}] nodes)
                  :resources (mapv (fn [span] {:id (str "work/" (get span "span_id")) :parent nil
                                               :source "trace" :kind :logical
                                               :label (str "wait4 work scope: " (get span "name"))})
                                   (filter #(get % "rusage") spans))
                  :edges [] :measurements (into [] (mapcat #(measurements header %)) spans)}]
      ;; Even with open markers, a final record after an end stamp contradicts the capture.
      (when (and end-time (some #(pos? (decimal/compare (or (graph/node-end %) (graph/node-start %)) end-time)) nodes))
        (trace-io/refuse! :trace-clock "end.json" []))
      (canonical/normalize-graph (graph/require-valid! result)))))
(m/=> project [:=> [:cat t/Journal t/Inventory] c/Graph])
