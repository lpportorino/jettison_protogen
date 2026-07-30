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

   TEXT-WRAPPED renders one WRAP-long-mode label too narrow for its string and
   an otherwise identical wide twin. Only the narrow one may carry
   `text_wrapped` — a key no other flag can stand in for, because a WRAP label
   GROWS instead of clipping, so `text_clipped` (CLIP-mode only) and
   `text_truncated` (dot_begin) both stay absent while the reader gets a
   mid-word break.

   INVERTED-CONTENT-BOX is the DISCRIMINATOR for the guards that suppress an
   artifact without suppressing a real defect. All three cards share one parent
   — 40x40 at :pad-all 24, so its content box is [24 24 15 15] and is not a
   rectangle at all.

   A 10x10 child lands at the content origin and sits WHOLLY INSIDE the
   parent's coords: nothing is clipped, so `clipped` may not fire, and the
   parent's extent — positive from the inverted term alone — must be DECLINED.
   A 30x30 child in the same parent runs to x2/y2 53 against the parent's own
   39: `clipped` fires on the child, which is where the defect is reported, and
   the parent's extent is declined THERE TOO, because LVGL's number cannot tell
   the two cases apart. Pinning both directions is the point — neither blanket
   is correct. The childless twin covers `obj_has_no_content`, a different
   clause reached by a different term of LVGL's extent sum, and the one-axis
   card pins that the decline is PER AXIS.

   REVERT-TO-BREAK, three separate clauses. The two mask mutations BOTH red the
   one-axis arm, and they red it for OPPOSITE reasons — which is why that arm
   asserts the flag is present AND that the axis string is exactly VER:
     - delete the `pc = parent->coords` fallback in `obj_clipped` -> the
       inside-child `clipped` arm reds, alone. Measured.
     - delete the two per-axis masks in `obj_overflow_dirs` -> THREE arms red:
       both parent-side DECLINED arms, plus the one-axis arm, which reports
       scroll_dirs BOTH instead of VER. The extra HOR bit is itself an
       artifact — a 10px-wide child cannot overflow a 40px box — so this
       mutation ADDS a false axis. Measured, and the first draft of this clause
       claimed the one-axis arm stayed green, which running it disproved.
     - replace those masks with a whole-box `content_box_inverted` early return
       -> ONLY the one-axis arm reds, and this time by LOSING the genuine VER
       bit (scrollable_overflow goes absent). Measured.
   The two-axis fixtures cannot separate those last two mutations at all: both
   of their axes are inverted, so per-axis and whole-box agree there. That is
   what the one-axis card exists for, and why its assertion reads both halves.

   Every case is in the renderer battery. Optional args select one mutation
   canary at a time:

     clojure -M:bindings:dump-contract-probe overflow-visible
     clojure -M:bindings:dump-contract-probe truncated
     clojure -M:bindings:dump-contract-probe text-wrapped
     clojure -M:bindings:dump-contract-probe inverted-content-box"
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

(defn- long-mode-card
  "The same over-long string at the same narrow width in a NON-WRAP long mode.
   `text_wrapped` must stay absent: the clause is WRAP-scoped, and without this
   arm deleting its `long_mode != WRAP` check leaves every other arm green while
   the flag starts firing on DOTS and CLIP labels — which the corpus's
   :probe-defect cells would then silently swallow."
  [mode]
  (fixtures/build-authored-card
   canvas
   {:id (str "canary/long-mode-" (name mode))
    :node {:type :WIDGET_OBJ
           :bare true
           :props {:w 800 :h 480 :pad-all 0 :border-width 0}
           :children [{:type :WIDGET_LABEL
                       :text "Sharpen amount wraps here"
                       :props {:w 40 :h 18
                               :label_props {:long_mode mode}}}]}}))

(defn- wrap-card
  "One WRAP-long-mode label at `w`, same string either way. Bare root so the
   harness contributes no padding of its own."
  [w]
  (fixtures/build-authored-card
   canvas
   {:id (str "canary/wrap-" w)
    :node {:type :WIDGET_OBJ
           :bare true
           :props {:w 800 :h 480 :pad-all 0 :border-width 0}
           :children [{:type :WIDGET_LABEL
                       :text "Sharpen amount wraps here"
                       :props {:w w
                               :label_props {:long_mode :wrap}}}]}}))

(defn- text-wrapped-checks []
  (let [narrow (node-of-type (render-tree! (wrap-card 40)) "lv_label")
        wide (node-of-type (render-tree! (wrap-card 400)) "lv_label")
        h (fn [n] (let [[_ y1 _ y2] (:coords n)] (inc (- y2 y1))))]
    [[(true? (:text_wrapped narrow))
      (format "narrow label text_wrapped=%s coords %s (h %d)"
              (pr-str (:text_wrapped narrow)) (pr-str (:coords narrow)) (h narrow))]
     [(nil? (:text_wrapped wide))
      (format "wide CONTROL text_wrapped=%s coords %s (h %d)"
              (pr-str (:text_wrapped wide)) (pr-str (:coords wide)) (h wide))]
     ;; The two other text flags must stay absent on BOTH, or a reader could
     ;; conclude the wrap was reportable without this key.
     [(and (nil? (:text_clipped narrow)) (nil? (:text_truncated narrow)))
      (format "narrow label text_clipped=%s text_truncated=%s (neither may stand in)"
              (pr-str (:text_clipped narrow)) (pr-str (:text_truncated narrow)))]
     ;; A wrap is a GROWTH, so the narrow label must be taller than the wide
     ;; one — the geometric fact the key reports, asserted independently of it.
     [(> (h narrow) (h wide))
      (format "narrow h %d > wide h %d" (h narrow) (h wide))]
     ;; WRAP-SCOPING CONTROLS. Same string, same narrow width, other long modes.
     ;; REVERT-TO-BREAK: delete the `lv_label_get_long_mode(obj) !=
     ;; LV_LABEL_LONG_MODE_WRAP` early return in `label_text_wrapped`. These two
     ;; arms red; the four above them are the control and stay green.
     ;; Each control asserts BOTH halves: text_wrapped absent, AND the mode's
     ;; own flag PRESENT. The second half is what stops the arm being vacuous —
     ;; `nil?` alone is satisfied by the long-mode guard whatever the geometry
     ;; does, so on its own it would pass even for a label that comfortably fits
     ;; and would prove nothing about the guard.
     (let [dots (node-of-type (render-tree! (long-mode-card :dots)) "lv_label")]
       [(and (nil? (:text_wrapped dots)) (true? (:text_truncated dots)))
        (format "DOTS CONTROL text_wrapped=%s text_truncated=%s (must be nil/true)"
                (pr-str (:text_wrapped dots)) (pr-str (:text_truncated dots)))])
     (let [clip (node-of-type (render-tree! (long-mode-card :clip)) "lv_label")]
       [(and (nil? (:text_wrapped clip)) (true? (:text_clipped clip)))
        (format "CLIP CONTROL text_wrapped=%s text_clipped=%s (must be nil/true)"
                (pr-str (:text_wrapped clip)) (pr-str (:text_clipped clip)))])]))

(def ^:private inverted-pad
  "Pad per side on a 40px box: the content box becomes [24 24 15 15], i.e.
   x1 > x2 and y1 > y2. Stock LVGL's own PAD_DEF is this number, which is why
   the case is reachable rather than contrived."
  24)

(defn- inverted-card
  "A 40x40 parent at `inverted-pad`, holding one `child-size` square child, or
   none when `child-size` is nil."
  [child-size]
  (fixtures/build-authored-card
   canvas
   {:id (str "canary/inverted-" child-size)
    :node {:type :WIDGET_OBJ
           :bare true
           :props {:w 800 :h 480 :pad-all 0 :border-width 0}
           :children [{:type :WIDGET_OBJ
                       :props {:w 40 :h 40 :pad-all inverted-pad :border-width 0}
                       :children (if child-size
                                   [{:type :WIDGET_OBJ
                                     :props {:w child-size :h child-size}}]
                                   [])}]}}))

(defn- inverted-parent
  "The 40x40 node, found by size rather than by type — every node here is an
   lv_obj, so type cannot identify it."
  [tree]
  (first (filter #(= [40 40] (let [[x1 y1 x2 y2] (:coords %)]
                               [(inc (- x2 x1)) (inc (- y2 y1))]))
                 (node-seq tree))))

(defn- one-axis-card
  "A parent inverted on ONE axis only: 40 wide (content x [24..15], inverted at
   :pad-all 24) and 200 tall (content y [24..175], a valid span). Its child is
   tall enough to overflow that valid vertical span for real.

   This is the card the both-axes fixture cannot be: there, a whole-box
   `content_box_inverted` test and a per-axis one agree on every arm, so the
   per-axis property would be unpinned and a regression to the whole-box form
   would pass. Here they disagree, which is the only way to hold it."
  []
  (fixtures/build-authored-card
   canvas
   {:id "canary/one-axis-inverted"
    :node {:type :WIDGET_OBJ
           :bare true
           :props {:w 800 :h 480 :pad-all 0 :border-width 0}
           :children [{:type :WIDGET_OBJ
                       :props {:w 40 :h 200 :pad-all inverted-pad :border-width 0}
                       :children [{:type :WIDGET_OBJ :props {:w 10 :h 400}}]}]}}))

(defn- one-axis-parent [tree]
  (first (filter #(= [40 200] (let [[x1 y1 x2 y2] (:coords %)]
                                [(inc (- x2 x1)) (inc (- y2 y1))]))
                 (node-seq tree))))

(defn- inverted-content-box-checks []
  (let [inside (render-tree! (inverted-card 10))
        escaping (render-tree! (inverted-card 30))
        empty-tree (render-tree! (inverted-card nil))
        p-in (inverted-parent inside)
        p-esc (inverted-parent escaping)
        p-empty (inverted-parent empty-tree)
        p-1ax (one-axis-parent (render-tree! (one-axis-card)))
        child (fn [p] (first (:children p)))]
    [[(and (nil? (:overflow p-in)) (nil? (:scrollable_overflow p-in)))
      (format "inside-child parent overflow=%s scrollable_overflow=%s coords %s"
              (pr-str (:overflow p-in)) (pr-str (:scrollable_overflow p-in))
              (pr-str (:coords p-in)))]
     [(nil? (:clipped (child p-in)))
      (format "inside child clipped=%s coords %s in parent %s"
              (pr-str (:clipped (child p-in)))
              (pr-str (:coords (child p-in))) (pr-str (:coords p-in)))]
     ;; THE CONTROL, and it is the PAIR that matters rather than either half.
     ;; Same inverted pad, a child that really does leave the parent's coords.
     ;; The two arms below say: the defect IS reported (child-side `clipped`),
     ;; and the parent-side extent is DECLINED (both flags absent).
     ;;
     ;; Asserting them together is what makes the decline auditable. With only
     ;; the `clipped` arm, a reviewer could not tell a declared decline from a
     ;; hole — and could not tell either from the opposite error, which was also
     ;; measured: removing the decline makes the INSIDE-child parent report
     ;; overflow for content wholly within it. Neither blanket is correct, so
     ;; the contract is pinned in both directions here.
     [(true? (:clipped (child p-esc)))
      (format "escaping child clipped=%s coords %s in parent %s"
              (pr-str (:clipped (child p-esc)))
              (pr-str (:coords (child p-esc))) (pr-str (:coords p-esc)))]
     [(and (nil? (:overflow p-esc)) (nil? (:scrollable_overflow p-esc)))
      (format "escaping-child parent overflow=%s scrollable_overflow=%s — the
              parent-side answer is DECLINED on an inverted axis"
              (pr-str (:overflow p-esc)) (pr-str (:scrollable_overflow p-esc)))]
     [(and (nil? (:overflow p-empty)) (nil? (:scrollable_overflow p-empty)))
      (format "childless parent overflow=%s scrollable_overflow=%s coords %s"
              (pr-str (:overflow p-empty)) (pr-str (:scrollable_overflow p-empty))
              (pr-str (:coords p-empty)))]
     ;; PER-AXIS. One axis inverted, the other a valid span with real overflow
     ;; on it: the valid axis must still answer, and must name itself.
     ;; REVERT-TO-BREAK: replace the two per-axis tests in `obj_overflow_dirs`
     ;; with a single whole-box `content_box_inverted` early return. Only this
     ;; arm reds; every arm above it is the control and stays green.
     [(and (some? (:scrollable_overflow p-1ax))
           (= "ver" (:scroll_dirs p-1ax)))
      (format "one-axis-inverted parent scrollable_overflow=%s scroll_dirs=%s coords %s"
              (pr-str (:scrollable_overflow p-1ax)) (pr-str (:scroll_dirs p-1ax))
              (pr-str (:coords p-1ax)))]]))

(def ^:private cases
  {"overflow-visible" overflow-visible-checks
   "truncated" truncated-checks
   "text-wrapped" text-wrapped-checks
   "inverted-content-box" inverted-content-box-checks})

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
