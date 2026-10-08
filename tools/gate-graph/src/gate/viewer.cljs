(ns gate.viewer
  "Human-first offline timeline. Canonical observations stay separate from reversible presentation."
  (:require [clojure.string :as str]
            [gate.admission :as admission]
            [gate.archive :as archive]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as d]
            [gate.graph :as graph]
            [gate.inspection-contract :as ic]
            [gate.query :as query]
            [gate.view.contract :as vc]
            [gate.view.dom :as dom]
            [gate.view.model :as model]
            [gate.view.opportunity :as opportunity]
            [malli.core :as m]))

(def ^:private context (atom nil))
(def ^:private state (atom {:anchor nil :fold nil :offset 0 :search "" :failures false :window [0 1] :tracks #{}}))
(def ^:private history (atom []))
(def ^:private series (atom {}))
(def ^:private investigations (atom {:longest nil :candidates []}))
(def ^:private last-instant (atom "0"))
(def ^:private budget {:visits 128000 :rows 100 :bytes 1048576})
(def ^:private problems #{:failed :error :blocked :refused :cancelled :running})
(def ^:private opener (atom nil))
(def ^:private last-activated (atom nil))
(declare render! navigate! inspect!)

(def ^:private OpenerKey
  ;; :row is unbounded on purpose: a fold's data-members joins every member Id.
  [:map {:closed true} [:scope [:maybe [:string {:max 64}]]] [:row [:maybe :string]] [:text :string]])

(defn- row-identity
  "The identity of the lane, fold or resource track a control sits in, if any."
  [element]
  (some-> (.closest element "[data-node],[data-members],[data-track]")
          (as-> row (or (.getAttribute row "data-node") (.getAttribute row "data-members")
                        (.getAttribute row "data-track")))))
(m/=> row-identity [:=> [:cat some?] [:maybe :string]])

(defn- opener-key
  "Identify a control by what survives a re-render: its panel, its row and its label."
  [element]
  (when-let [button (and element (.closest element "button"))]
    {:scope (some-> (.closest button "#opportunities,#breadcrumbs,#timeline,#resources,#details") .-id)
     :row (row-identity button)
     :text (str/trim (.-textContent button))}))
(m/=> opener-key [:=> [:cat [:maybe some?]] [:maybe OpenerKey]])

(defn- find-opener
  "Find the control a key names in the CURRENT document, after any re-render."
  [{:keys [scope row text]}]
  (let [root (or (when scope (.getElementById js/document scope)) js/document)]
    (some (fn [button]
            (when (and (= text (str/trim (.-textContent button))) (= row (row-identity button)))
              button))
          (array-seq (.querySelectorAll root "button")))))
(m/=> find-opener [:=> [:cat OpenerKey] [:maybe some?]])

(defn- remember-opener!
  "Record WHICH control opened the details dialog, as a key, before any re-render replaces it.
   The activating click is preferred to focus: Safari and iOS do not focus a button on click."
  []
  (when-not (.-open (.getElementById js/document "detail-dialog"))
    (reset! opener (or @last-activated (opener-key (.-activeElement js/document)))))
  (reset! last-activated nil)
  nil)
(m/=> remember-opener! [:=> [:cat] :nil])

(defn- focus-timeline!
  "After a navigation, put keyboard focus at the start of the new context, not on <body>."
  []
  (when-let [heading (.getElementById js/document "timeline-heading")]
    (.focus heading (js-obj "preventScroll" true)))
  nil)
(m/=> focus-timeline! [:=> [:cat] :nil])

(defn- plural
  "Count with the right noun form; zero and many take the plural."
  [n singular plural-form]
  (str n "\u00a0" (if (= 1 n) singular plural-form)))
(m/=> plural [:=> [:cat [:int {:min 0}] [:string {:min 1}] [:string {:min 1}]] [:string {:min 3}]])

(defn- graph-value
  "Return the immutable admitted graph owned by this document."
  [] (get-in @context [:query :graph]))
(m/=> graph-value [:=> [:cat] c/Graph])

(defn- duration
  "Format display-only nanoseconds with adaptive units; exact values remain in EDN."
  [value]
  (let [n (js/Number value)
        [scale unit] (cond (>= n 1e9) [1e9 "s"] (>= n 1e6) [1e6 "ms"] (>= n 1e3) [1e3 "µs"] :else [1 "ns"])]
    (str (js/parseFloat (.toFixed (/ n scale) 2)) " " unit)))
(m/=> duration [:=> [:cat c/Natural] [:string {:max 80}]])

(defn- extent
  "Open captures end at the last observation, without inventing a finish for an open task."
  []
  @last-instant)
(m/=> extent [:=> [:cat] c/Natural])

(defn- capture-extent
  "Include unowned resource observations in an unfinished capture's last observed instant."
  [value]
  (or (get-in value [:run :end-ns])
      (reduce (fn [a b] (if (pos? (d/compare a b)) a b)) "0"
              (concat (map #(or (graph/node-end %) (graph/node-start %)) (:nodes value))
                      (map #(get-in % [:interval :end-ns]) (:measurements value))))))
(m/=> capture-extent [:=> [:cat c/Graph] c/Natural])

(defn- overview-anchor
  "Open a sole container's children; keep a sole leaf visible in the run overview."
  []
  (let [roots (get-in @context [:children nil]) id (:id (first roots))]
    (when (and (= 1 (count roots)) (seq (get-in @context [:children id]))) id)))
(m/=> overview-anchor [:=> [:cat] [:maybe c/Id]])

(defn- fraction-instant
  "Round a presentation fraction at a fixed precision using integer arithmetic, never a large float timestamp."
  [fraction]
  (str (js* "((BigInt(~{}) * BigInt(Math.round(~{} * 1e12))) / BigInt(1000000000000))" (extent) fraction)))
(m/=> fraction-instant [:=> [:cat number?] c/Natural])

(defn- visible-interval
  "Preserve exact evidence windows; ordinary navigation uses bounded display fractions."
  []
  (or (:exact-window @state)
      {:start-ns (fraction-instant (first (:window @state))) :end-ns (fraction-instant (second (:window @state)))}))
(m/=> visible-interval [:=> [:cat] c/Interval])

(defn- position
  "Map exact run-relative time to presentation coordinates; no absolute clock origin is coerced."
  [instant]
  (let [{:keys [start-ns end-ns]} (visible-interval)
        before? (neg? (d/compare instant start-ns))
        offset (js/Number (if before? (d/subtract start-ns instant) (d/subtract instant start-ns)))
        span (js/Number (d/subtract end-ns start-ns))]
    (if (zero? span) 0 (* (if before? -100 100) (/ offset span)))))
(m/=> position [:=> [:cat c/Natural] number?])

(defn- label
  "Retain status in the accessible name as well as color."
  [node] (str (:label node) " · " (name (:outcome node))))
(m/=> label [:=> [:cat c/Node] [:string {:max 550}]])

(defn- write-anchor!
  "Persist identity and presentation state in a local fragment; no network or storage dependency."
  []
  (let [params (js/URLSearchParams.)]
    (.set params "task" (or (:anchor @state) ""))
    (.set params "q" (:search @state))
    (.set params "window" (str/join "," (:window @state)))
    (.set params "page" (str (:offset @state)))
    (.set params "fold" (str/join "," (:fold @state)))
    (.set params "attention" (str (:failures @state)))
    (when-let [interval (:exact-window @state)] (.set params "exact" (str (:start-ns interval) "," (:end-ns interval))))
    (.replaceState js/history nil "" (str "#" (.toString params)))
    nil))
(m/=> write-anchor! [:=> [:cat] :nil])

(defn navigate!
  "Remember prior zoom/filter state and open an exact parent or reversible fold."
  [anchor fold]
  (swap! history #(vec (take-last 64 (conj % @state))))
  (swap! state assoc :anchor anchor :fold fold :offset 0 :search "")
  (write-anchor!) (render!)
  (.scrollIntoView (.getElementById js/document "breadcrumbs") (js-obj "block" "start"))
  (focus-timeline!)
  nil)
(m/=> navigate! [:=> [:cat [:maybe c/Id] [:maybe [:vector {:min 1 :max 256} c/Id]]] :nil])

(defn- show-page!
  "Display exact bounded evidence with an explicit continuation button."
  [panel request]
  (let [page (model/page @context request)]
    (.append panel (dom/raw-details "Exact view query" (canonical/encode request 65536))
             (dom/raw-details "Exact query and evidence" (canonical/encode page 1048576)))
    (when-let [cursor (:next page)]
      (let [more (dom/button "More evidence" #(do (show-page! panel (assoc request :cursor cursor)) nil))]
        (.addEventListener more "click" (fn [_] (.remove more)))
        (.append panel more)))
    nil))
(m/=> show-page! [:=> [:cat dom/Element vc/Request] :nil])

(defn- show-query!
  "Retain bounded core query continuations, including windows and per-task measurements."
  [panel request]
  (let [page (query/page (:query @context) request)]
    (.append panel (dom/raw-details "Exact core query" (canonical/encode request 65536))
             (dom/raw-details "Exact core query and evidence" (canonical/encode page 1048576)))
    (when-let [cursor (:next page)]
      (let [more (dom/button "More query evidence" #(do (show-query! panel (assoc request :cursor cursor)) nil))]
        (.addEventListener more "click" (fn [_] (.remove more)))
        (.append panel more)))
    nil))
(m/=> show-query! [:=> [:cat dom/Element ic/Request] :nil])

(defn inspect!
  "Open dismissible task details and bounded typed relationships/correlation without inventing causes."
  [node]
  (remember-opener!)
  (swap! state assoc :selected (:id node))
  (render!)
  (let [panel (dom/clear! "details")
        dialog (.getElementById js/document "detail-dialog")
        interval (:interval node)]
    (.append panel (dom/el "h2" (:label node))
             (dom/el "p" (str (name (:outcome node)) " · " (:id node))))
    (if (= :decision (:record node))
      (.append panel (dom/el "p" (str "Decision only · " (get-in node [:reason :detail]))))
      (.append panel (dom/el "p" (if (:end-ns interval)
                                   (str (duration (d/subtract (:end-ns interval) (:start-ns interval))) " elapsed")
                                   "Unfinished · final duration unknown"))))
    (when (seq (get-in @context [:children (:id node)]))
      (.append panel (dom/button "Open children" #(do (.close dialog) (navigate! (:id node) nil)))))
    (.append panel (dom/raw-details "Canonical task" (canonical/encode node 65536)))
    (.append panel (dom/el "h3" "Dependencies and boundary edges"))
    (let [edges (model/page @context {:select {:op :edges :members [(:id node)]} :budget budget})]
      (doseq [edge (:rows edges)]
        (let [other (if (= (:id node) (get-in edge [:from :node])) (get-in edge [:to :node]) (get-in edge [:from :node]))]
          (.append panel (dom/button (str (get-in edge [:from :node]) " → " (get-in edge [:to :node]) " · " (name (:kind edge)) " · " (name (:evidence edge)))
                                     #(inspect! (get-in @context [:nodes other]))))))
      (when (empty? (:rows edges))
        (.append panel (dom/el "p" (if (= :complete (:stop edges)) "No recorded dependency edges. Ordering alone is not evidence."
                                       "No matching edges in this inspected prefix. Continue the bounded evidence query below.")))))
    (show-page! panel {:select {:op :edges :members [(:id node)]} :budget budget})
    (.append panel (dom/el "h3" "Resource coincidence")
             (dom/el "p" "Shared run clock. Overlap is context, not evidence that this task caused machine load. Unjoined clocks cannot enter this graph."))
    (when (:end-ns interval) (show-page! panel {:select {:op :correlation :interval interval} :budget budget}))
    (show-query! panel {:select {:op :measurements :where {:node (:id node)}} :budget budget})
    (when-not (.-open dialog) (.showModal dialog))
    nil))
(m/=> inspect! [:=> [:cat c/Node] :nil])

(defn- tick-ruler
  "Label elapsed time shared by graph and resource strips."
  []
  (let [axis (dom/el "div" "" "ruler") {:keys [start-ns end-ns]} (visible-interval)]
    (doseq [i (range 5)]
      (let [tick (dom/el "span" (duration (str (js* "(BigInt(~{}) + ((BigInt(~{}) - BigInt(~{})) * BigInt(~{})) / BigInt(4))" start-ns end-ns start-ns i))))]
        (set! (.. tick -style -left) (str (* 25 i) "%"))
        (.append axis tick)))
    axis))
(m/=> tick-ruler [:=> [:cat] dom/Element])

(defn- segment!
  "Draw true interval width, clipped at the selected window. The separate label is the touch target."
  [track start end class-name]
  (let [left (max 0 (position start)) right (min 100 (position end))]
    (when (>= right left)
      (let [bar (dom/el "span" "" (str "bar " class-name))]
        (set! (.. bar -style -left) (str left "%"))
        (set! (.. bar -style -width) (str (- right left) "%"))
        (when (and (= left 100) (str/includes? class-name "decision"))
          (set! (.. bar -style -transform) "translateX(-100%)"))
        (.append track bar))))
  nil)
(m/=> segment! [:=> [:cat dom/Element c/Natural c/Natural [:string {:max 80}]] :nil])

(defn- task-row
  "Render a logical task lane with separate selection and child navigation."
  [node]
  (let [row (dom/el "div" "" "row") head (dom/el "div" "" "row-head") track (dom/el "div" "" "track")
        children (get-in @context [:children (:id node)])]
    (.setAttribute row "data-node" (:id node))
    (.append head (dom/button (label node) #(inspect! node)))
    (when (seq children) (.append head (dom/button (str "Open " (count children) " children") #(navigate! (:id node) nil))))
    (segment! track (graph/node-start node) (or (graph/node-end node) (extent)) (str (name (:outcome node)) (when (= :decision (:record node)) " decision")))
    (set! (.-title track) (str (label node) " · " (if (= :decision (:record node)) "decision, no execution" "elapsed time")))
    (.append row head track) row))
(m/=> task-row [:=> [:cat c/Node] dom/Element])

(defn- fold-row
  "Keep exact members, failures, gaps and independent duration meanings in a reversible fold."
  [nodes]
  (let [fold (model/fold nodes) row (dom/el "div" "" "row fold-row") head (dom/el "div" "" "row-head")
        track (dom/el "div" "" "track") failures (reduce + 0 (map #(get (:outcomes fold) % 0) problems))]
    (.setAttribute row "data-members" (str/join " " (:members fold)))
    (.append head (dom/button (str (count nodes) " grouped tasks · " (plural failures "needs attention" "need attention")) #(navigate! (:anchor @state) (:members fold))))
    (.append head (dom/button "Group evidence"
                              #(let [_ (remember-opener!) panel (dom/clear! "details") dialog (.getElementById js/document "detail-dialog")]
                                 (.append panel (dom/el "h2" "Exact group membership and boundary edges")
                                          (dom/raw-details "Fold summary" (canonical/encode fold 1048576)))
                                 (show-page! panel {:select {:op :members :members (:members fold)} :budget budget})
                                 (show-page! panel {:select {:op :edges :members (:members fold)} :budget budget})
                                 (.showModal dialog) nil)))
    (doseq [interval (:occupied fold)] (segment! track (:start-ns interval) (:end-ns interval) (if (pos? failures) "failed" "fold")))
    (.append row head track
             (dom/el "p" (str "Envelope " (duration (get-in fold [:summary :envelope-ns]))
                              " · occupied " (duration (get-in fold [:summary :occupied-ns]))
                              " · summed durations " (duration (get-in fold [:summary :duration-sum-ns]))
                              " · " (plural (:decisions fold) "decision" "decisions") " · " (plural (:unfinished fold) "unfinished" "unfinished")) "fold-summary"))
    row))
(m/=> fold-row [:=> [:cat [:vector {:min 1 :max 256} c/Node]] dom/Element])

(defn- selected-nodes
  "Filter at the selected hierarchy, or search all identities so failures remain reachable."
  []
  (let [{:keys [anchor fold search failures]} @state
        all (cond (or failures (not (str/blank? search))) (get-in @context [:query :ordered-nodes])
                  fold (mapv #(get-in @context [:nodes %]) fold)
                  :else (get-in @context [:children anchor] []))]
    (->> all
         (filter #(and (or (not failures) (contains? problems (:outcome %)))
                       (str/includes? (str/lower-case (str (:id %) " " (:key %) " " (:label %) " " (name (:outcome %)))) (str/lower-case search))))
         (sort (fn [a b] (let [c (d/compare (or (graph/node-start a) "0") (or (graph/node-start b) "0"))]
                           (if (zero? c) (compare (:id a) (:id b)) c))))
         vec)))
(m/=> selected-nodes [:=> [:cat] model/Nodes])

(defn- connectors!
  "Map folded endpoints back to their exact original instants; cap routing at 120 edges with disclosure."
  [panel nodes]
  (let [visible (set (map :id nodes))
        rows (array-seq (.querySelectorAll panel ".row"))
        index (into {} (mapcat (fn [row] (map #(vector % row)
                                              (str/split (or (.getAttribute row "data-members") (.getAttribute row "data-node") "") #" "))) rows))
        edges (filter #(and (visible (get-in % [:from :node])) (visible (get-in % [:to :node]))
                            (not (identical? (get index (get-in % [:from :node])) (get index (get-in % [:to :node]))))) (:edges (graph-value)))
        edges (sort-by #(if (or (= (:selected @state) (get-in % [:from :node])) (= (:selected @state) (get-in % [:to :node]))) 0 1) edges)
        svg (dom/svg "svg" {:class "connectors" :aria-hidden "true"}) box (.getBoundingClientRect panel)
        ;; Two layers: every casing below every edge, so no edge's casing can notch
        ;; another edge or its arrowhead where they cross.
        casings (dom/svg "g" {:class "casings"}) strokes (dom/svg "g" {:class "edges"})]
    (.append svg casings strokes)
    (doseq [edge (take 120 edges)]
      (let [a (get-in @context [:nodes (get-in edge [:from :node])]) b (get-in @context [:nodes (get-in edge [:to :node])])
            from (get index (:id a)) to (get index (:id b))
            instant (fn [node phase] (if (= :finish phase) (graph/node-end node) (graph/node-start node)))
            start (instant a (get-in edge [:from :phase])) end (instant b (get-in edge [:to :phase]))]
        (when (and from to start end (not (identical? from to)) (<= 0 (position start) 100) (<= 0 (position end) 100))
          (let [f (.getBoundingClientRect (.querySelector from ".track")) t (.getBoundingClientRect (.querySelector to ".track"))
                x1 (+ (- (.-left f) (.-left box)) (* (.-width f) (/ (position start) 100)))
                x2 (+ (- (.-left t) (.-left box)) (* (.-width t) (/ (position end) 100)))
                y1 (+ (- (.-top f) (.-top box)) 14) y2 (+ (- (.-top t) (.-top box)) 14)]
            (.append casings (dom/svg "path" {:d (str "M" x1 "," y1 " L" x2 "," y2 " l-5,-4 m5,4 l-5,4")
                                              :data-casing-for (:id edge)
                                              :class (str "edge-casing"
                                                          (when (or (= (:selected @state) (:id a)) (= (:selected @state) (:id b))) " selected-casing"))}))
            (.append strokes (dom/svg "path" {:d (str "M" x1 "," y1 " L" x2 "," y2 " l-5,-4 m5,4 l-5,4")
                                              :data-edge (:id edge) :data-from (:id a) :data-to (:id b)
                                              :class (str "edge " (name (:evidence edge))
                                                          (when (or (= (:selected @state) (:id a)) (= (:selected @state) (:id b))) " selected-edge"))}))))))
    (when (> (count edges) 120) (.append panel (dom/el "p" (str "Routing first 120 of " (count edges) " visible edges; exact boundary evidence remains in details."))))
    (.append panel svg) nil))
(m/=> connectors! [:=> [:cat dom/Element model/Nodes] :nil])

(defn- lane-groups
  "Fold only short entries at this visible scale; keep substantial work individually discoverable."
  [nodes]
  (let [short? (fn [node] (or (= :decision (:record node))
                              (and (graph/node-end node) (< (- (position (graph/node-end node)) (position (graph/node-start node))) 2))))]
    (if (or (:fold @state) (<= (count nodes) 24)) (mapv vector nodes)
        (vec (mapcat (fn [run] (if (short? (first run)) (map vec (partition-all 128 run)) (map vector run)))
                     (partition-by short? nodes))))))
(m/=> lane-groups [:=> [:cat model/Nodes] [:vector {:max 128000} [:vector {:min 1 :max 256} c/Node]]])

(defn- render-timeline!
  "Bound visible lanes to 24 and fold dense levels into exact batches of at most 256."
  []
  (let [panel (dom/clear! "timeline") all (selected-nodes)
        groups (lane-groups all) offset (min (:offset @state) (* 24 (quot (max 0 (dec (count groups))) 24)))
        shown (subvec groups offset (min (+ offset 24) (count groups)))]
    (swap! state assoc :offset offset)
    (.append panel (doto (dom/el "h2" (if (:fold @state) "Inside the group" "Execution timeline"))
                     (.setAttribute "id" "timeline-heading") (.setAttribute "tabindex" "-1"))
             (dom/el "p" (str (plural (count all) "matching task" "matching tasks") " · " (plural (count shown) "visible lane" "visible lanes") " · " (plural (- (count groups) (count shown)) "other lane" "other lanes") ". Logical lanes; width is elapsed time.") "caption")
             (tick-ruler))
    (doseq [group shown] (.append panel (if (= 1 (count group)) (task-row (first group)) (fold-row group))))
    (when (empty? all) (.append panel (dom/el "p" "No matching tasks. Clear the search or return to the overview.")))
    (when (pos? offset) (.append panel (dom/button "Previous lanes" #(do (swap! state update :offset (fn [n] (max 0 (- n 24)))) (render!) nil))))
    (when (< (+ offset 24) (count groups)) (.append panel (dom/button "Next lanes" #(do (swap! state update :offset + 24) (render!) nil))))
    (.append panel (dom/el "p" (str (count (:edges (graph-value))) " recorded edges · solid: observed → · dashed: declared →. Containment is navigation. Overlap does not imply dependency. Select a task for typed boundary edges.") "caption"))
    (connectors! panel (vec (mapcat identity shown))) nil))
(m/=> render-timeline! [:=> [:cat] :nil])

(defn- track-name
  "Describe resource identity and quantity without guessing device type from its label."
  [[resource quantity]]
  (let [r (first (filter #(= resource (:id %)) (:resources (graph-value))))]
    (str (:label r) " · " (name quantity))))
(m/=> track-name [:=> [:cat [:tuple c/Id c/Quantity]] [:string {:max 600}]])

(defn- inspect-track!
  "Expose scope, coverage and exact samples to touch and keyboard users, independently of tiny marks."
  [track-key]
  (remember-opener!)
  (let [panel (dom/clear! "details") dialog (.getElementById js/document "detail-dialog")]
    (.append panel (dom/el "h2" (track-name track-key))
             (dom/el "p" "Sample evidence uses the admitted run clock. Each interval is its actual cadence/coverage; source, method, task scope, accounting and unavailable reasons remain explicit below. A sampled gauge does not prove what happened between samples."))
    (show-query! panel {:select {:op :measurements :where {:resource (first track-key) :quantity (second track-key)}} :budget budget})
    (.showModal dialog) nil))
(m/=> inspect-track! [:=> [:cat [:tuple c/Id c/Quantity]] :nil])

(defn- resource-strip
  "Render measured sample intervals only, with gaps and unavailable observations left empty."
  [track-key observations]
  (let [row (doto (dom/el "div" "" "resource-row") (.setAttribute "data-track" (pr-str track-key)))
        track (dom/el "div" "" "resource-track")
        quantity (second track-key) unit (name (:unit (c/quantity-info quantity)))
        homogeneous? (<= (count (set (map #(select-keys % [:source :node :form :accounting :method]) observations))) 1)
        known (when homogeneous? (filter #(some? (opportunity/value %)) observations))
        rate? (= :delta (:form (first observations)))
        factor (if rate? (case unit "ns" 1 "us" 100000 "byte" (/ 1e9 1048576) 1e9) (if (= unit "byte") (/ 1 1048576) 1))
        unit (if rate? (case unit "ns" "CPU equivalents (1 = one logical CPU)" "us" "% interval stall time" "byte" "MiB/s throughput" (str unit "/s")) (if (= unit "byte") "MiB sampled footprint" unit))
        magnitude (fn [sample] (* factor (opportunity/value sample)))
        maximum (reduce max 1 (map magnitude known))]
    (.append row (dom/el "p" (str (track-name track-key) " · " unit " · " (count observations) " observations") "track-label"))
    (doseq [sample known]
      (let [bar (dom/el "span" "" "sample")
            left (max 0 (position (get-in sample [:interval :start-ns]))) right (min 100 (position (get-in sample [:interval :end-ns])))
            height (* 100 (/ (magnitude sample) maximum))]
        (when (>= right left)
          (set! (.. bar -style -left) (str left "%"))
          (set! (.. bar -style -width) (str (- right left) "%"))
          (set! (.. bar -style -height) (str height "%"))
          (when (= (get-in sample [:interval :start-ns]) (get-in sample [:interval :end-ns]))
            (set! (.. bar -style -width) "2px")
            (when (= left 100) (set! (.. bar -style -transform) "translateX(-100%)")))
          (set! (.-title bar) (str (.toFixed (magnitude sample) 2) " " unit " · " (name (:status sample)) " · " (name (:accounting sample)) " · " (name (:method sample))))
          (.append track bar))))
    (.append row track
             (dom/el "p" (str "Scale 0–" (.toFixed maximum 2) " " unit "; independent observations, no interpolation or summation. "
                              (count (remove #(= :measured (:status %)) observations)) " uncertain/unavailable. Points mark instantaneous samples. Raw counters are not rates; gaps are unknown. "
                              (when-not homogeneous? "Mixed scopes/forms: chart withheld; inspect exact evidence.")) "caption")
             (dom/button "Inspect track evidence" #(inspect-track! track-key)))
    row))
(m/=> resource-strip [:=> [:cat [:tuple c/Id c/Quantity] [:vector {:max 1000000} c/Measurement]] dom/Element])

(defn- render-resources!
  "Align selected evidence to the timeline; absence is an explicit state, never a zero-valued chart."
  []
  (let [panel (dom/clear! "resources")
        tracks @series
        selected (:tracks @state)]
    (.append panel (dom/el "h2" "Resource context")
             (dom/el "p" "Same run clock and time window. Machine activity is context, not task attribution." "caption"))
    (if (empty? tracks)
      (.append panel (dom/el "p" "CPU · host memory · I/O pressure: not collected. Per-core, GPU and disk evidence unavailable." "absent"))
      (let [chooser (dom/el "select" "") wrap (dom/el "label" "Add a resource track ")]
        (.setAttribute chooser "aria-label" "Add a resource track")
        (.append chooser (dom/el "option" "Choose resource / core / device"))
        (doseq [[index track-key] (map-indexed vector (sort (keys tracks)))]
          (let [option (dom/el "option" (track-name track-key))]
            (set! (.-value option) (str index)) (.append chooser option)))
        (.addEventListener chooser "change"
                           (fn [_] (when (re-matches #"[0-9]+" (.-value chooser))
                                     (swap! state update :tracks conj (nth (vec (sort (keys tracks))) (js/Number (.-value chooser))))
                                     (render-resources!)))
                           (.append wrap chooser) (.append panel wrap (tick-ruler))
                           (doseq [track-key (take 8 (sort selected)) :when (get tracks track-key)]
                             (let [observations (get tracks track-key)]
                               (.append panel (resource-strip track-key (vec (take 512 observations)))
                                        (dom/button (str "Remove " (track-name track-key)) #(do (swap! state update :tracks disj track-key) (render-resources!) nil)))
                               (when (> (count observations) 512)
                                 (.append panel (dom/el "p" (str "Showing first 512 of " (count observations) " observations. Remaining data stays in canonical EDN; this is truncation, not downsampling.")))))))
        (when (> (count selected) 8) (.append panel (dom/el "p" "8-track display limit. Remove a track to reveal additional selections.")))))
    nil))
(m/=> render-resources! [:=> [:cat] :nil])

(defn- render-navigation!
  "Offer an explicit escape path from every hierarchy and preserve the prior presentation state."
  []
  (let [nav (dom/clear! "breadcrumbs") selected (get-in @context [:nodes (:anchor @state)])]
    (.append nav (dom/button "Run overview" #(do (reset! history [])
                                                 (swap! state assoc :anchor (overview-anchor)
                                                        :fold nil :offset 0 :search "" :failures false :window [0 1] :exact-window nil)
                                                 (render!) nil)))
    (when (seq @history)
      (.append nav (dom/button "← Back" #(do (reset! state (peek @history)) (swap! history pop) (render!) nil))))
    (when (:parent selected)
      (.append nav (dom/button "↑ Parent" #(navigate! (:parent selected) nil))))
    (when selected (.append nav (dom/button (:label selected) #(inspect! selected))))
    (when (:fold @state) (.append nav (dom/el "span" "Grouped tasks" "crumb")))
    nil))
(m/=> render-navigation! [:=> [:cat] :nil])

(defn- focus-window!
  "Select an evidence interval and list coincident tasks without inferring attribution."
  [interval]
  (let [total (js/Number (extent)) panel (dom/clear! "details") dialog (.getElementById js/document "detail-dialog")
        page (query/page (:query @context) {:select {:op :window :interval interval} :budget budget})]
    (when (pos? total) (swap! state assoc :window [(/ (js/Number (:start-ns interval)) total) (/ (js/Number (:end-ns interval)) total)]))
    (swap! state assoc :exact-window interval)
    (render!)
    (.append panel (dom/el "h2" "Investigate this interval")
             (dom/el "p" "Coincident tasks below. Eligibility and a better schedule are unknown until dependencies, claims and workload are checked."))
    (doseq [node (:rows page)] (.append panel (dom/button (label node) #(inspect! node))))
    (show-query! panel {:select {:op :window :interval interval} :budget budget})
    (.showModal dialog) nil))
(m/=> focus-window! [:=> [:cat c/Interval] :nil])

(defn- inspect-candidate!
  "Connect a heuristic interval to its exact supporting IDs, active gates and a falsifiable next step."
  [candidate]
  (remember-opener!)
  (focus-window! (:interval candidate))
  (let [panel (.getElementById js/document "details")
        instruction (case (:quantity candidate)
                      (:bytes-read :bytes-written) "Compare disk activity with I/O pressure and device scope. If contention aligns, stagger competing gates or overlap complementary CPU work; preserve dependency and exclusive-resource constraints."
                      :memory-current-bytes "Check memory pressure, reclaim/faults and phase changes. A footprint drop is not lost useful work; do not fill RAM merely to raise occupancy."
                      :gpu-utilization-percent "Check GPU feeder CPU work, transfer/wait evidence and device sharing before changing concurrency."
                      "Check whether independent gates were eligible during the CPU dip, then inspect dispatch, waits, prerequisites and quota pressure. Test overlap only when readiness and resource ownership permit it.")]
    (.prepend panel (doto (dom/el "h2" (str (name (:kind candidate)) " · " (name (:quantity candidate)) " · " (:resource candidate) " · "))
                      ;; The time range never wraps inside itself ("100 / ms–112 ms").
                      (.append (dom/el "span" (str (duration (get-in candidate [:interval :start-ns])) "–" (duration (get-in candidate [:interval :end-ns]))) "nowrap")))
              (dom/el "p" (str "Next experiment: " instruction " Compare full-run elapsed time and unchanged workload/verdict coverage across interleaved runs."))
              (dom/raw-details "Candidate policy and exact measurement IDs" (canonical/encode candidate 65536)))
    nil))
(m/=> inspect-candidate! [:=> [:cat opportunity/Candidate] :nil])

(defn- render-opportunities!
  "Lead with actionable investigations and their evidence gaps, not decorative metric totals."
  []
  (let [panel (dom/clear! "opportunities")
        {:keys [longest candidates]} @investigations]
    (.append panel (dom/el "h2" "Where to investigate"))
    (let [failing (count (filter #(problems (:outcome %)) (:nodes (graph-value))))]
      (when (pos? failing)
        (.append panel (dom/button (str (plural failing "task needs attention" "tasks need attention") " · show")
                                   #(do (swap! state assoc :failures true :offset 0) (render!) (focus-timeline!) nil)))))
    (when longest
      (.append panel (dom/button (str "Longest gate: " (:label longest) " · " (duration (d/subtract (graph/node-end longest) (graph/node-start longest))) " · inspect") #(inspect! longest))))
    (if (empty? @series)
      (.append panel (dom/el "p" "CPU/RAM/I/O not collected. Add run-clock telemetry to investigate idle capacity and contention." "caption"))
      (let [detail (dom/el "details" "")]
        (.append detail (dom/el "summary" (str (count candidates) " activity-change candidates · inspect scheduling opportunities"))
                 (dom/el "p" "Rule v1: 20 contiguous measured baseline samples; 3 consecutive samples below half or above twice the baseline median. First 64 series, 512 observations per series; up to 6 candidates. Not a bottleneck verdict or speedup estimate." "caption"))
        (doseq [candidate candidates]
          ;; The time range is a no-wrap span: same text, but it never splits at its dash.
          (.append detail (doto (dom/button (str (name (:kind candidate)) " · " (name (:quantity candidate)) " · " (:resource candidate) " · ")
                                            #(inspect-candidate! candidate))
                            (.append (dom/el "span" (str (duration (get-in candidate [:interval :start-ns])) "–" (duration (get-in candidate [:interval :end-ns]))) "nowrap")))))
        (when (empty? candidates) (.append detail (dom/el "p" "No qualifying change in the inspected sample prefix. Sparse, reset or absent evidence cannot establish steady utilization.")))
        (.append detail (dom/el "p" "Try: inspect eligible work during CPU dips; stagger competing disk-heavy gates when bursts coincide with pressure. Memory footprint changes do not establish useful work. Compare interleaved runs with identical coverage."))
        (.append panel detail)))
    nil))
(m/=> render-opportunities! [:=> [:cat] :nil])

(defn render!
  "Render bounded lanes and resource tracks, leaving controls stable for keyboard focus."
  []
  (render-navigation!) (render-opportunities!) (render-timeline!) (render-resources!)
  (set! (.-value (.getElementById js/document "search")) (:search @state))
  (set! (.-value (.getElementById js/document "window-start")) (* 100 (first (:window @state))))
  (set! (.-value (.getElementById js/document "window-end")) (* 100 (second (:window @state))))
  (.setAttribute (.getElementById js/document "attention-toggle") "aria-pressed" (str (:failures @state)))
  (write-anchor!) nil)
(m/=> render! [:=> [:cat] :nil])

(defn- controls!
  "Install native search, failure, window and theme controls once."
  []
  (let [search (.getElementById js/document "search")
        controls (.getElementById js/document "view-controls")]
    (let [cursor (.getElementById js/document "time-cursor") start (.getElementById js/document "window-start") end (.getElementById js/document "window-end")]
      (.addEventListener cursor "input" (fn [_] (.setProperty (.. js/document -documentElement -style) "--cursor" (str (.-value cursor) "%"))))
      (doseq [input [start end]]
        (.addEventListener input "change" (fn [_] (let [a (/ (js/Number (.-value start)) 100) b (/ (js/Number (.-value end)) 100)]
                                                    (when (< a b) (swap! state assoc :window [a b] :exact-window nil) (render!)))))))
    (.addEventListener search "input" (fn [_] (swap! state assoc :search (.-value search) :offset 0) (render!)))
    (.append (.getElementById js/document "attention-controls")
             (doto (dom/button "Needs attention" #(do (swap! state update :failures not) (swap! state assoc :offset 0) (render!) nil))
               (.setAttribute "id" "attention-toggle")))
    (.append controls
             (dom/button "Zoom in" #(do (swap! state assoc :exact-window nil) (swap! state update :window (fn [[a b]] (if (< (- b a) 1e-9) [a b] (let [q (/ (- b a) 4)] [(+ a q) (- b q)])))) (render!) nil))
             (dom/button "← Pan" #(do (swap! state assoc :exact-window nil) (swap! state update :window (fn [[a b]] (let [delta (min a (/ (- b a) 2))] [(- a delta) (- b delta)]))) (render!) nil))
             (dom/button "Pan →" #(do (swap! state assoc :exact-window nil) (swap! state update :window (fn [[a b]] (let [delta (min (- 1 b) (/ (- b a) 2))] [(+ a delta) (+ b delta)]))) (render!) nil))
             (dom/button "Reset time" #(do (swap! state assoc :window [0 1] :exact-window nil) (render!) nil))
             (dom/button "Light / dark" #(do (.toggleAttribute (.-documentElement js/document) "data-light") nil)))
    ;; Focus is restored SYNCHRONOUSLY, in the task that closes the dialog (the
    ;; Close button, or `cancel` — Escape, which fires before the dialog closes).
    ;; close() itself returns focus to the opener; the fallback runs only when that
    ;; opener was re-rendered away. The async `close` event is a fallback only for a
    ;; close that skipped `cancel` (an Escape with no recent user activation). It is
    ;; queued, so it can arrive after the dialog was opened AGAIN — then it belongs
    ;; to a closed dialog that no longer exists and does nothing, leaving the new
    ;; opener alone, and it acts only while focus is LOST — on the body or inside
    ;; the closed dialog — so it never takes focus back from a user who moved on.
    ;; Close and Escape act in the user's own task, so a known opener ALWAYS gets
    ;; focus there: close() may have handed it to whatever held focus before the
    ;; dialog opened (a Safari click does not focus the button it activates).
    (.addEventListener js/document "click" (fn [event] (reset! last-activated (opener-key (.-target event)))) true)
    (let [dialog (.getElementById js/document "detail-dialog")
          place-focus! (fn [e only-if-lost?]
                         (let [found (when e (find-opener e))
                               active (.-activeElement js/document)
                               lost? (or (nil? active) (= active (.-body js/document)) (.contains dialog active))]
                           (when (or lost? (and found (not only-if-lost?)))
                             ;; The first candidate that ACTUALLY takes focus: a re-rendered opener
                             ;; can sit inside a closed <details>, where .focus() silently does nothing.
                             (some (fn [candidate]
                                     (when candidate
                                       (.focus candidate)
                                       (= candidate (.-activeElement js/document))))
                                   [found
                                    (some-> found (.closest "details:not([open])") (.querySelector "summary"))
                                    (when-let [id (:selected @state)]
                                      (.querySelector js/document (str "[data-node=\"" (js/CSS.escape id) "\"] button")))
                                    (.getElementById js/document "search")]))))
          dismiss! (fn []
                     (let [e @opener]
                       (reset! opener nil)
                       (.close dialog)
                       (place-focus! e false)))]
      (.addEventListener (.getElementById js/document "close-details") "click" (fn [_] (dismiss!)))
      (.addEventListener dialog "cancel" (fn [event] (.preventDefault event) (dismiss!)))
      (.addEventListener dialog "close"
                         (fn [_] (when-not (.-open dialog)
                                   (let [e @opener] (reset! opener nil) (place-focus! e true))))))
    (.addEventListener js/window "resize" (fn [_] (render-timeline!)))
    nil))
(m/=> controls! [:=> [:cat] :nil])

(defn- restore-anchor!
  "Treat fragments as untrusted presentation hints; only existing identities and finite windows survive."
  []
  (let [params (js/URLSearchParams. (subs (.-hash js/location) 1)) task (.get params "task") q (.get params "q")
        window (mapv js/Number (str/split (or (.get params "window") "0,1") #","))
        offset (js/Number (or (.get params "page") "0"))
        exact (str/split (or (.get params "exact") "") #",")]
    (swap! state assoc :fold nil :exact-window nil :failures (= "true" (.get params "attention")))
    (when (and (js/Number.isInteger offset) (<= 0 offset 128000)) (swap! state assoc :offset offset))
    (when (and (= 2 (count exact)) (every? #(re-matches #"(?:0|[1-9][0-9]{0,39})" %) exact)
               (neg? (d/compare (first exact) (second exact))) (d/before-or-equal? (second exact) (extent)))
      (swap! state assoc :exact-window {:start-ns (first exact) :end-ns (second exact)}))
    (let [members (str/split (or (.get params "fold") "") #",")]
      (when (and (<= 1 (count members) 256) (= (count members) (count (set members))) (every? #(contains? (:nodes @context) %) members))
        (swap! state assoc :fold (vec members))))
    (when (contains? (:nodes @context) task) (swap! state assoc :anchor task))
    (when (and q (<= (count q) 512)) (swap! state assoc :search q))
    (when (and (= 2 (count window)) (every? js/Number.isFinite window) (<= 0 (first window)) (< (first window) (second window)) (<= (second window) 1))
      (swap! state assoc :window window))
    nil))
(m/=> restore-anchor! [:=> [:cat] :nil])

(defn init!
  "Admit graph and archive identity before enabling interactions; refuse malformed evidence visibly."
  []
  (try
    (let [value (admission/decode (.-textContent (.getElementById js/document "gate-data")) :graph admission/default-limits)]
      (when-let [metadata (.getElementById js/document "archive-data")]
        (archive/require-valid! (assoc (admission/decode (.-textContent metadata) :archive-metadata
                                                         (assoc admission/default-limits :bytes 8388608)) :graph value)))
      (reset! context (model/prepare value))
      (reset! last-instant (capture-extent value))
      (reset! series (group-by (juxt :resource :quantity) (:measurements value)))
      (let [gates (filter #(and (= :gate (:kind %)) (= :execution (:record %)) (graph/node-end %)) (:nodes value))]
        (reset! investigations
                {:longest (first (sort #(d/compare (d/subtract (graph/node-end %2) (graph/node-start %2))
                                                   (d/subtract (graph/node-end %1) (graph/node-start %1))) gates))
                 :candidates (vec (take 6 (mapcat (fn [[_ samples]] (opportunity/detect (vec (take 512 samples)))) (take 64 (sort-by (fn [[[resource quantity] _]]
                                                                                                                                       [(cond (= :host (:kind (first (filter #(= resource (:id %)) (:resources value))))) 0
                                                                                                                                              (contains? #{:bytes-read :bytes-written} quantity) 1
                                                                                                                                              (= quantity :gpu-utilization-percent) 2 :else 3)
                                                                                                                                        resource quantity]) @series)))))}))
      (swap! state assoc :anchor (overview-anchor))
      (let [host-ids (set (map :id (filter #(= :host (:kind %)) (:resources value))))]
        (swap! state assoc :tracks (set (take 3 (sort (distinct (map (juxt :resource :quantity) (filter #(and (nil? (:node %)) (host-ids (:resource %))) (:measurements value)))))))))
      (set! (.-textContent (.getElementById js/document "status"))
            (str (if (get-in value [:run :complete?]) "Complete capture" "Incomplete capture") " · " (duration (extent))
                 " elapsed · " (plural (count (:nodes value)) "task" "tasks") " · " (plural (count (:measurements value)) "measurement" "measurements") " · "
                 (plural (count (filter #(problems (:outcome %)) (:nodes value))) "needs attention" "need attention")))
      (restore-anchor!) (controls!) (render!)
      ;; Last: if anything above throws, the catch shows the refusal with the
      ;; controls still hidden, never controls that could only do nothing.
      (set! (.-hidden (.getElementById js/document "interactive-controls")) false))
    (catch :default error
      (when-let [status (.getElementById js/document "archive-status")] (set! (.-textContent status) "Archive refused"))
      (set! (.-textContent (.getElementById js/document "status")) (str "Report refused: " (pr-str (ex-data error))))))
  nil)
(m/=> init! [:=> [:cat] :nil])
