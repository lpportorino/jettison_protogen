(ns devcards.invariants
  "Per-card DOM + emission invariants — the devcard gate's non-pixel lane.

   Pure: data in (parsed dump_tree JSON, captured host emissions, card meta),
   findings out (vector of finding maps). The caller renders; this judges.

   The invariant set (the corpus contract):
   - NO layout-defect flag on any node: text_truncated / text_clipped /
     clipped / overflow / scrollable_overflow / offscreen / squished. A card
     exists to show a widget correctly; a defect flag is the renderer saying
     it could not.
   - NO zero-area node (coords collapse to w=0 or h=0).
   - NO zero-visible-area node (`vis_px` 0 = fully occluded/clipped away —
     the field is emitted only when visible < total, so 0 is the occlusion
     signal). Capability-gated: the caller declares :vis-px? from the module
     it actually loaded; the check never silently no-ops.
   - The dump is COMPLETE: a root `truncated` sentinel makes the card
     unjudgeable — HARD finding, never a skip.
   - Emissions: :commands, :reports, :events are EMPTY for every card;
     :proxy-reports empty for every card EXCEPT a host_proxy card, which
     must carry EXACTLY ONE (positively asserted — absence is a finding).

   Designed-geometry exclusions (each a narrow, verified LVGL widget
   contract, NEVER a blanket suppression — see the predicate docstrings for
   the per-rule rationale + the regression each rule still catches):
   - a hidden ancestor's descendants skip :zero-visible-area only;
   - lv_tabview's content child is a scroll-snap page carousel: the content
     may carry :scrollable_overflow, and a page fully snapped outside the
     content box (plus its subtree) may carry :clipped / :zero-visible-area;
   - lv_roller's drum: vertical-only overflow of its options label is the
     wheel illusion (roller :overflow; label :clipped/:offscreen);
   - `designed-scroll-classes` (lv_table) may carry :scrollable_overflow.

   Exemptions are per-card + per-invariant, proof-carrying, ratchet-down:
   every entry needs :rationale + :retires-when, and an exemption that
   matches NO finding is itself a finding (:stale-exemption) so the list can
   only shrink. No entry is currently live: the anticipated lv_line class
   (renderer has no line_props decode arm) yields ZERO findings because its
   cards mandate explicit finite w/h — an empty box, not a collapsed or
   flagged one — so the corpus runs exemption-free until a real unsolvable
   arrives."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def defect-flags
  "Layout-defect keys the dump emits only when set (main.c dump_obj)."
  [:text_truncated :text_clipped :clipped :overflow :scrollable_overflow :offscreen
   :squished])

(defn- node-area
  "[w h] from a dumped node's coords [x1 y1 x2 y2] (inclusive), or nil."
  [node]
  (when-let [[x1 y1 x2 y2] (:coords node)] [(inc (- x2 x1)) (inc (- y2 y1))]))

(defn- node-label [node] (str (:type node) (when-let [uid (:uid node)] (str "#" uid))))

(defn- walk-nodes
  "Depth-first seq of every node map in a dump tree."
  [root]
  (tree-seq #(seq (:children %)) :children root))

;; ── Designed-geometry exclusions ────────────────────────────────────────
;; Each predicate names ONE verified LVGL widget contract and stays narrow
;; enough that the defect class it exempts cannot hide an unrelated
;; regression: every rule keys on the widget class (dump `type`) plus the
;; exact geometry the design produces, never on the flag alone.
(def designed-scroll-classes
  "Widget classes whose content exceeding the viewport IS the widget's
   contract, so :scrollable_overflow is its normal state, not a defect.
   lv_table: cells are drawn content behind a scrolling viewport (LVGL
   ships it with scrollbar + SCROLLED styles); even a width-matched table
   scrolls by its own border inset (content box = w - 2*border < the col
   span). A real table-geometry regression still lands in the pixel
   goldens. Closed set — extend deliberately, with the class's design
   argument, never to silence a finding."
  #{"lv_table"})

(defn- h-within?
  "Is `inner`'s horizontal span contained in `outer`'s (coords
   [x1 y1 x2 y2])? The roller-drum rules hinge on this: vertical escape is
   the wheel design, horizontal escape is a real clip (pad/width
   regression squeezing the option text) and stays a finding."
  [inner outer]
  (let [[ix1 _ ix2 _] (:coords inner)
        [ox1 _ ox2 _] (:coords outer)]
    (and ix1 ox1 (>= ix1 ox1) (<= ix2 ox2))))

(defn- boxes-intersect?
  "Do two [x1 y1 x2 y2] boxes share any pixel?"
  [a b]
  (let [[ax1 ay1 ax2 ay2] a
        [bx1 by1 bx2 by2] b]
    (and ax1 bx1 (<= ax1 bx2) (>= ax2 bx1) (<= ay1 by2) (>= ay2 by1))))

(defn- roller-drum-overflow?
  "lv_roller renders visible_row_count rows of an options label that is
   BY DESIGN taller than the drum (lv_roller.c: the wheel illusion is one
   tall label the widget clips and translates). Its :overflow is exempt
   only while every child label stays horizontally inside the roller box."
  [node]
  (and (= "lv_roller" (:type node))
       (seq (:children node))
       (every? #(h-within? % node) (:children node))))

(defn- drum-label-escape?
  "The roller's internal options label: :clipped/:offscreen are the drum
   design (vertical translate past the box/display edge) only while the
   label stays horizontally inside its roller."
  [node parent]
  (and (= "lv_roller_label" (:type node))
       (= "lv_roller" (:type parent))
       (h-within? node parent)))

(defn- annotate
  "Depth-first vector of {:node :parent :hidden-under? :snapped-under?
   :tabview-content?} for every node. :hidden-under? = some ancestor
   carries :hidden. :tabview-content? = the node is child index 1 of an
   lv_tabview (the scroll-snap page carousel; child 0 is the tab bar —
   lv_tabview_constructor creates bar then content, and the stock theme
   dispatches on the same indices). :snapped-under? = the node is, or sits
   under, a DIRECT child of that content whose box does not intersect the
   content box — a page the carousel has snapped fully out of view. The
   ACTIVE page intersects and stays fully judged, so a real clip or
   occlusion inside visible page content still fails."
  ([root] (annotate root nil {:hidden? false :snapped? false :content? false}))
  ([node parent {:keys [hidden? snapped? content?]}]
   (let [child-hidden? (or hidden? (boolean (:hidden node)))
         tabview? (= "lv_tabview" (:type node))]
     (into [{:node node
             :parent parent
             :hidden-under? hidden?
             :snapped-under? snapped?
             :tabview-content? content?}]
           (comp (map-indexed (fn [i child]
                                (annotate child
                                          node
                                          {:hidden? child-hidden?
                                           :snapped? (or snapped?
                                                         (and content?
                                                              (not (boxes-intersect?
                                                                    (:coords child)
                                                                    (:coords node)))))
                                           :content? (and tabview? (= 1 i))})))
                 cat)
           (:children node)))))

(defn- designed-flag?
  "Is `flag` on this annotated node one of the designed-geometry cases the
   ns docstring sanctions? Anything else is a live finding."
  [{:keys [node parent snapped-under? tabview-content?]} flag]
  (or (and (= flag :scrollable_overflow)
           (or (contains? designed-scroll-classes (:type node)) tabview-content?))
      (and (contains? #{:clipped :offscreen} flag)
           (or snapped-under? (drum-label-escape? node parent)))
      (and (= flag :overflow) (roller-drum-overflow? node))))

(defn tree-findings
  "DOM findings for one card's parsed dump tree. `caps` declares what the
   loaded module can express: {:vis-px? bool}. Returns finding maps
   {:card :invariant :node :detail}."
  [card-id root caps]
  (when-not (map? root)
    (throw (ex-info "dump tree root must be a parsed map"
                    {:card card-id :got (type root)})))
  (let [annotated (annotate root)]
    (-> []
        (into (when (:truncated root)
                [{:card card-id
                  :invariant :dump-truncated
                  :node "(root)"
                  :detail "dump_tree overflowed its buffer — card unjudgeable"}]))
        (into (for [entry annotated
                    flag defect-flags
                    :when (and (get (:node entry) flag) (not (designed-flag? entry flag)))]
                {:card card-id
                 :invariant flag
                 :node (node-label (:node entry))
                 :detail (str "layout defect flag " (name flag))}))
        (into (for [node (walk-nodes root)
                    :let [[w h] (node-area node)]
                    :when (and w (or (zero? w) (zero? h)) (not (:hidden node)))]
                {:card card-id
                 :invariant :zero-area
                 :node (node-label node)
                 :detail (str "collapsed to " w "x" h)}))
        (into (when (:vis-px? caps)
                ;; a subtree under a :hidden ancestor is invisible by
                ;; declaration (the dropdown's closed popup list), and a
                ;; snapped-away carousel page is invisible by design —
                ;; neither zero is occlusion. Everything else with vis_px 0
                ;; is a genuinely occluded/clipped-away node.
                (for [{:keys [node hidden-under? snapped-under?]} annotated
                      :when (and (contains? node :vis_px)
                                 (zero? (:vis_px node))
                                 (not (:hidden node))
                                 (not hidden-under?)
                                 (not snapped-under?))]
                  {:card card-id
                   :invariant :zero-visible-area
                   :node (node-label node)
                   :detail "vis_px 0 — fully occluded or clipped away"}))))))

(defn emission-findings
  "Emission findings for one card. `emissions` = {:commands [..] :reports
   [..] :events [..] :proxy-reports [..]} as captured by the host;
   `host-proxy?` marks the one card class whose SINGLE proxy-report is the
   POSITIVE contract."
  [card-id emissions host-proxy?]
  (let [n (fn [k] (count (get emissions k)))]
    (-> []
        (into (for [k [:commands :reports :events]
                    :when (pos? (n k))]
                {:card card-id
                 :invariant :unexpected-emission
                 :node (name k)
                 :detail
                 (str (n k) " " (name k) " captured — a devcard must " "emit none")}))
        (into (cond (and host-proxy? (not= 1 (n :proxy-reports)))
                    [{:card card-id
                      :invariant :proxy-report-contract
                      :node ":proxy-reports"
                      :detail (str "host_proxy card must emit EXACTLY 1 "
                                   "proxy-report, got "
                                   (n :proxy-reports))}]
                    (and (not host-proxy?) (pos? (n :proxy-reports)))
                    [{:card card-id
                      :invariant :unexpected-emission
                      :node ":proxy-reports"
                      :detail (str (n :proxy-reports)
                                   " proxy-reports from a non-host_proxy card")}]
                    :else [])))))

(defn- exemption-error
  [entry problem]
  (throw (ex-info (str "malformed exemption: " problem) {:entry entry})))

(defn validate-exemptions!
  "Exemptions shape check — every entry {:card <string-or-regex-string>
   :invariant <kw> :rationale <ne-string> :retires-when <ne-string>}, no
   other keys. Throws on the first malformed entry; returns the list."
  [exemptions]
  (doseq [e exemptions]
    (when-not (map? e) (exemption-error e "not a map"))
    (when-let [extra (seq (remove #{:card :invariant :rationale :retires-when} (keys e)))]
      (exemption-error e (str "unknown keys " (vec extra))))
    (when-not (string? (:card e)) (exemption-error e ":card must be a string"))
    (when-not (keyword? (:invariant e)) (exemption-error e ":invariant must be a keyword"))
    (doseq [k [:rationale :retires-when]]
      (when-not (and (string? (get e k)) (not (str/blank? (get e k))))
        (exemption-error
         e
         (str k " must be a non-blank string — the proof " "is mandatory")))))
  exemptions)

(defn- exempt?
  [exemption finding]
  (and (= (:invariant exemption) (:invariant finding))
       (re-matches (re-pattern (:card exemption)) (str (:card finding)))))

(defn apply-exemptions
  "Split findings against the proof-carrying exemption list. Returns
   {:live [..] :exempted [..] :stale-exemptions [..]} — an exemption that
   matched nothing is STALE and reported as its own finding class, so the
   list ratchets down by construction."
  [findings exemptions]
  (let [exemptions (validate-exemptions! exemptions)
        matched (set (for [e exemptions f findings :when (exempt? e f)] e))
        live (vec (remove (fn [f] (some #(exempt? % f) exemptions)) findings))
        stale (vec (remove matched exemptions))]
    {:live live
     :exempted (vec (remove (set live) findings))
     :stale-exemptions (mapv (fn [e]
                               {:card (:card e)
                                :invariant (:invariant e)
                                :invariant-class :stale-exemption
                                :detail "exemption matched no finding — remove it"})
                             stale)}))

(defn card-findings
  "The full judgment for one rendered card: DOM + emissions, exemptions
   applied. Returns {:live [..] :exempted [..] :stale-exemptions [..]};
   the gate fails on any :live or :stale-exemptions entry."
  [{:keys [card-id tree emissions host-proxy? caps exemptions]
    :or {emissions {} exemptions []}}]
  (apply-exemptions (into (tree-findings card-id tree (or caps {:vis-px? false}))
                          (emission-findings card-id emissions host-proxy?))
                    exemptions))