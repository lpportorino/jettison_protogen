(ns gate.viewer
  "Offline bounded drill UI. Shared CLJC admission and query code owns data semantics."
  (:require [gate.admission :as admission]
            [gate.archive :as archive]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as decimal]
            [gate.graph :as graph]
            [gate.inspection-contract :as ic]
            [gate.query :as query]
            [malli.core :as m]))

(def Element [:fn #(instance? js/Element %)])
(def Callback [:=> [:cat] :nil])
(def Tag [:enum "button" "div" "h2" "h3" "p" "pre"])
(def DisplayText [:string {:max 1048576}])
(def ^:private context (atom nil))
(def ^:private nodes (atom {}))
(def ^:private budget {:visits 128000 :rows 200 :bytes 1048576})

(defn- element
  "Create content through textContent, never interpret labels as HTML."
  [tag text]
  (let [result (.createElement js/document tag)]
    (set! (.-textContent result) text)
    result))
(m/=> element [:=> [:cat Tag DisplayText] Element])

(defn- container
  "Resolve a fixed report element and clear its previous children."
  [id]
  (let [result (.getElementById js/document id)]
    (.replaceChildren result)
    result))
(m/=> container [:=> [:cat [:enum "timeline" "breadcrumbs" "details"]] Element])

(defn- button
  "Use native focusable buttons for all drill actions."
  [label callback]
  (let [result (element "button" label)]
    (.addEventListener result "click" (fn [_] (callback)))
    result))
(m/=> button [:=> [:cat c/Label Callback] Element])

(defn- show-details!
  "Show exact node and bounded measurement pages, with explicit continuation controls."
  [node cursor]
  (let [panel (container "details")
        request (cond-> {:select {:op :measurements :where {:node (:id node)}} :budget budget}
                  cursor (assoc :cursor cursor))
        page (query/page @context request)]
    (.append panel (element "h2" (:label node))
             (element "pre" (canonical/encode node 65536))
             (element "h3" "Measurements")
             (element "pre" (canonical/encode page 1048576)))
    (when-let [next-page (:next page)]
      (.append panel (button "Next measurements" #(show-details! node next-page))))
    nil))
(m/=> show-details! [:=> [:cat c/Node [:maybe ic/Cursor]] :nil])

(defn- bounds
  "Use the selected parent's interval; open work extends only to the latest observation."
  [anchor]
  (let [node (get @nodes anchor)
        start (if node (graph/node-start node) "0")
        end (or (when node (graph/node-end node))
                (get-in @context [:graph :run :end-ns])
                (reduce (fn [latest task]
                          (let [instant (or (graph/node-end task) (graph/node-start task))]
                            (if (pos? (decimal/compare instant latest)) instant latest)))
                        start (vals @nodes)))]
    {:start-ns start :end-ns end}))
(m/=> bounds [:=> [:cat [:maybe c/Id]] c/Interval])

(defn- percent
  "Only display geometry uses floating point, after exact origin subtraction."
  [instant interval]
  (let [duration (decimal/subtract (:end-ns interval) (:start-ns interval))]
    (if (= "0" duration) 0
        (* 100 (/ (js/Number (decimal/subtract instant (:start-ns interval))) (js/Number duration))))))
(m/=> percent [:=> [:cat c/Natural c/Interval] [:double {:min 0 :max 100}]])

(declare drill!)

(defn- row
  "Render one logical lane with a native tooltip and separate inspect/drill actions."
  [node interval]
  (let [result (element "div" "") track (element "div" "") bar (element "div" "")
        start (graph/node-start node) end (or (graph/node-end node) (:end-ns interval))
        left (percent start interval) right (percent end interval)]
    (set! (.-className result) "row") (set! (.-className track) "track")
    (set! (.-className bar) (str "bar " (name (:outcome node))))
    (set! (.-title bar) (str (:label node) " · " (name (:outcome node)) " · " start "–"
                             (or (graph/node-end node) "open") " ns"))
    (set! (.. bar -style -left) (str left "%"))
    (set! (.. bar -style -width) (str (max 0 (- right left)) "%"))
    (.append track bar)
    (.append result (button (:label node) #(show-details! node nil)) track
             (button "Children →" #(drill! (:id node) nil)))
    result))
(m/=> row [:=> [:cat c/Node c/Interval] Element])

(defn drill!
  "Page immediate children at one scale; hidden work remains reachable through continuation."
  [anchor cursor]
  (let [panel (container "timeline") crumbs (container "breadcrumbs")
        interval (bounds anchor)
        request (cond-> {:select {:op :children :anchor anchor} :budget budget}
                  cursor (assoc :cursor cursor))
        page (query/page @context request)
        selected (get @nodes anchor)]
    (.append crumbs (button "Run overview" #(drill! nil nil)))
    (when selected
      (.append crumbs (button "↑ Parent" #(drill! (:parent selected) nil))
               (button (:label selected) #(show-details! selected nil))))
    (.append panel (element "p" (str (:start-ns interval) " → " (:end-ns interval)
                                     " ns from run origin. " (count (:rows page)) " rows; "
                                     (:visited page) " visited; " (name (:stop page)) ".")))
    (doseq [node (:rows page)] (.append panel (row node interval)))
    (when-let [next-page (:next page)]
      (.append panel (button "Next tasks" #(drill! anchor next-page))))
    (when (empty? (:rows page)) (.append panel (element "p" "No child tasks on this page.")))
    nil))
(m/=> drill! [:=> [:cat [:maybe c/Id] [:maybe ic/Cursor]] :nil])

(defn init!
  "Admit the embedded graph and optional archive binding before exposing any drill controls."
  []
  (try
    (let [data (.-textContent (.getElementById js/document "gate-data"))
          value (admission/decode data :graph admission/default-limits)]
      (when-let [metadata (.getElementById js/document "archive-data")]
        (archive/require-valid! (assoc (admission/decode (.-textContent metadata) :archive-metadata
                                                         (assoc admission/default-limits :bytes 8388608))
                                       :graph value)))
      (reset! context (query/prepare value))
      (reset! nodes (into {} (map (juxt :id identity) (:nodes value))))
      (set! (.-textContent (.getElementById js/document "status"))
            (str (if (get-in value [:run :complete?]) "Complete capture" "Incomplete capture")
                 " · " (count (:nodes value)) " tasks · " (count (:measurements value)) " measurements"))
      (drill! nil nil))
    (catch :default error
      (when-let [status (.getElementById js/document "archive-status")]
        (set! (.-textContent status) "Archive refused"))
      (set! (.-textContent (.getElementById js/document "status"))
            (str "Report refused: " (pr-str (ex-data error))))))
  nil)
(m/=> init! [:=> [:cat] :nil])
