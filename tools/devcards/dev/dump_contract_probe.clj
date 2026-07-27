(ns dump-contract-probe
  "Render-level canaries for the two dump contracts consumers rely on.

   OVERFLOW-VISIBLE builds the flag through devcards.fixtures, serialises it
   into WidgetNode.obj_flags, and renders a child wholly outside an lv_scale
   ancestor. lv_scale contributes a real 100px ext draw size through its LVGL
   class event. A sibling switch occupies the child's pixels. The no-flag twin
   must stay silent; the flagged card must dump a wider `descend_gate` and emit
   exactly one :overlap finding.

   TRUNCATED builds 1200 hidden labels through the same public fixture builder.
   They are cheap to render but their real dump is larger than TREE_BUF_SIZE.
   The host must recognise the renderer's suffix before parsing, return its
   canonical root, and make exactly one :dump-truncated finding reachable. A
   small twin proves normal JSON is still parsed as its full tree.

   Both cases are in the renderer battery. Optional args select one mutation
   canary at a time:

     clojure -M:bindings:dump-contract-probe overflow-visible
     clojure -M:bindings:dump-contract-probe truncated"
  (:require [clojure.data.json :as json]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]
            [devcards.overlap :as overlap])
  (:import [ui UiAst$Screen UiAst$WidgetNode]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(defn- render-tree!
  "Render protobuf bytes in a fresh host and parse dump-tree!'s JSON String."
  [^bytes pb]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try
      (host/render-card! h {:pb pb :bp 0 :dark 1})
      (json/read-str (host/dump-tree! h) :key-fn keyword)
      (finally (host/close! h)))))

(defn- node-seq [tree]
  (tree-seq #(seq (:children %)) :children tree))

(defn- node-of-type [tree type-name]
  (first (filter #(= type-name (:type %)) (node-seq tree))))

(defn- overlap-card
  "The only difference between the pair is the scale ancestor's wire flag.
   The authored switch sits 70px past a 40px parent, inside lv_scale's real
   100px ext draw size, and directly over the independent sibling switch."
  [overflow-visible?]
  (fixtures/build-authored-card
   canvas
   {:id (if overflow-visible? "canary/overflow-visible" "canary/coords-gate")
    :node
    {:type :WIDGET_OBJ
     :bare true
     :props {:w 320 :h 220 :pad-all 0 :border-width 0}
     :children
     [(cond-> {:type :WIDGET_SCALE
               :x 100
               :y 100
               :props {:w 40 :h 40}
               :children [{:type :WIDGET_SWITCH
                           :x 70
                           :y 0
                           :props {:w 40 :h 30}}]}
        overflow-visible? (assoc :flags [:overflow-visible]))
      {:type :WIDGET_SWITCH
       :x 170
       :y 100
       :props {:w 40 :h 30}}]}}))

(defn- wire-overflow-bits
  "obj_flags on root-wrap -> authored root -> scale."
  ^long [^bytes pb]
  (let [^UiAst$Screen screen (UiAst$Screen/parseFrom pb)
        ^UiAst$WidgetNode harness (.getRoot screen)
        ^UiAst$WidgetNode authored (.getChildren harness 0)
        ^UiAst$WidgetNode scale (.getChildren authored 0)]
    (.getObjFlags scale)))

(defn- overlap-findings [tree]
  (overlap/findings {:card-id "canary/overflow-visible"
                     :nodes (invariants/annotate-tree tree)
                     :classes lanes/overlap-classes
                     :thresholds {:gap-px 0}}))

(defn- overflow-visible-checks []
  (let [flagged-pb (overlap-card true)
        control-pb (overlap-card false)
        flagged (render-tree! flagged-pb)
        control (render-tree! control-pb)
        flagged-scale (node-of-type flagged "lv_scale")
        control-scale (node-of-type control "lv_scale")
        flagged-findings (overlap-findings flagged)
        control-findings (overlap-findings control)
        flagged-invariants (mapv :invariant flagged-findings)]
    [[(= 1048576 (wire-overflow-bits flagged-pb))
      (format "wire obj_flags = %d (want OVERFLOW_VISIBLE 1048576)"
              (wire-overflow-bits flagged-pb))]
     [(and (vector? (:descend_gate flagged-scale))
           (not= (:coords flagged-scale) (:descend_gate flagged-scale)))
      (format "dump scale coords %s, descend_gate %s"
              (pr-str (:coords flagged-scale))
              (pr-str (:descend_gate flagged-scale)))]
     [(and (nil? (:descend_gate control-scale))
           (empty? control-findings))
      (format "no-flag control descend_gate %s, findings %s"
              (pr-str (:descend_gate control-scale))
              (pr-str (mapv :invariant control-findings)))]
     [(and (= [:overlap] flagged-invariants)
           (= ["lv_switch vs lv_switch"] (mapv :node flagged-findings)))
      (format "overflow-visible findings %s on %s"
              (pr-str flagged-invariants)
              (pr-str (mapv :node flagged-findings)))]]))

(def ^:private oversized-child-count
  "Safely below MAX_LIVE_CHILDREN (4096), but far past TREE_BUF_SIZE once
   dump_obj emits each label's type, coords, text, visibility and style."
  1200)

(def ^:private flood-label
  {:type :WIDGET_LABEL
   :text "dump-contract-canary-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
   :flags [:hidden]
   :props {:w 1 :h 1}})

(defn- truncation-card [n]
  (fixtures/build-authored-card
   canvas
   {:id (str "canary/truncated-" n)
    :node {:type :WIDGET_OBJ
           :bare true
           :props {:w 800 :h 480 :pad-all 0 :border-width 0}
           :children (vec (repeat n flood-label))}}))

(defn- wire-child-count
  "Children on root-wrap -> authored root."
  ^long [^bytes pb]
  (let [^UiAst$Screen screen (UiAst$Screen/parseFrom pb)
        ^UiAst$WidgetNode harness (.getRoot screen)
        ^UiAst$WidgetNode authored (.getChildren harness 0)]
    (.getChildrenCount authored)))

(defn- oversized-result [^bytes pb]
  (try
    (let [tree (render-tree! pb)
          findings (invariants/tree-findings
                    "canary/truncated" tree {:vis-px? true})]
      {:ok (and (= {:truncated true :children []} tree)
                (= [:dump-truncated] (mapv :invariant findings)))
       :message
       (format "oversized host root %s, findings %s"
               (pr-str tree)
               (pr-str (mapv :invariant findings)))})
    (catch Throwable t
      {:ok false
       :message
       (format "oversized dump threw before :dump-truncated: %s: %s"
               (.getName (class t)) (.getMessage t))})))

(defn- truncated-checks []
  (let [^bytes large-pb (truncation-card oversized-child-count)
        ^bytes small-pb (truncation-card 8)
        small (render-tree! small-pb)
        small-nodes (count (node-seq small))
        result (oversized-result large-pb)]
    [[(and (= oversized-child-count (wire-child-count large-pb))
           (> (alength large-pb) 100000))
      (format "wire children = %d, protobuf bytes = %d"
              (wire-child-count large-pb) (alength large-pb))]
     [(and (not (:truncated small))
           (= 11 small-nodes))
      (format "small control parsed %d nodes with truncated=%s"
              small-nodes (pr-str (:truncated small)))]
     [(:ok result) (:message result)]]))

(def ^:private cases
  {"overflow-visible" overflow-visible-checks
   "truncated" truncated-checks})

(defn -main [& requested]
  (let [selected (if (seq requested) requested (keys cases))
        unknown (remove cases selected)
        checks (if (seq unknown)
                 [[false (str "unknown canary selector(s): " (pr-str (vec unknown)))]]
                 (mapcat #((get cases %)) selected))]
    (println "\n══ DUMP CONTRACT CANARIES ══")
    (doseq [[ok message] checks]
      (println (format "  %s  %s" (if ok "PASS" "FAIL") message)))
    (let [bad (count (remove first checks))]
      (println (format "\n%d/%d checks passed"
                       (- (count checks) bad) (count checks)))
      (when (pos? bad) (System/exit 1)))))
