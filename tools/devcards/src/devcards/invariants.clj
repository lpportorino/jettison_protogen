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
   - a HIDDEN node (and its subtree) skips :clipped and :zero-visible-area:
     LVGL declares a hidden object's geometry meaningless.
     lv_obj_is_layout_positioned returns false for it, so it is self-placed
     at its parent's content origin at its own LV_SIZE_CONTENT size; and the
     parent's calc_content_width/height SKIP hidden children, so the parent
     never grows to fit it. Its box is therefore self-derived and
     structurally unrelated to the parent's CONTENT box — which is the box
     obj_clipped compares it against. It draws nothing (vis_px 0), and when
     SHOWN the parent recomputes LV_SIZE_CONTENT and grows, so it is not
     clipped in that state either. :offscreen is deliberately NOT exempted:
     obj_offscreen compares the box against the DISPLAY rectangle, and the one
     ancestor test it does apply (obj_in_scroll_region) keys on scroll/snap
     flags and scroll overflow — never on the parent's content-box SIZING — so
     the hidden-child calc_content_* chain above does not reach it, and a
     parent growing cannot move a node back on-screen. That scroll-region
     escape is also what keeps snapped carousel pages and inactive tabview
     pages from firing here. The residue this accepts knowingly: a hidden,
     non-snappable child of a scroller whose ONLY overflow is that hidden child
     is not rescued by it (scroll extent skips hidden children), so its
     self-placed far edge can trip :offscreen. That direction is safe — it
     over-fires into a red gate rather than hiding a defect — and it is a
     difference of degree from :clipped, where the parent's content box
     excludes the hidden child BY CONSTRUCTION and the noise is therefore
     structural. The flags that do not depend on the
     parent's extent (:overflow, :text_truncated, :text_clipped, :squished)
     stay judged; the SHOWN state is proven by the consumer's own
     driven-subject variants, not by this lane;
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
  (:require [clojure.string :as str]
            [devcards.outcome :as outcome]))

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
;; Each predicate names ONE verified LVGL contract and stays narrow enough
;; that the defect class it exempts cannot hide an unrelated regression. Most
;; rules key on the widget class (dump `type`) plus the exact geometry the
;; design produces; the hidden-node rule instead keys on a property LVGL
;; itself declares — an object excluded from layout AND from its parent's
;; content calculation — and is therefore scoped to the single flag that
;; property's proof actually reaches. No rule keys on the flag alone.
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

(defn annotate-tree
  "Depth-first vector of {:node :parent :path :hidden-under? :snapped-under?
   :tabview-content?} for every node. :hidden-under? = some ancestor
   carries :hidden. :tabview-content? = the node is child index 1 of an
   lv_tabview (the scroll-snap page carousel; child 0 is the tab bar —
   lv_tabview_constructor creates bar then content, and the stock theme
   dispatches on the same indices). :snapped-under? = the node is, or sits
   under, a DIRECT child of that content whose box does not intersect the
   content box — a page the carousel has snapped fully out of view. The
   ACTIVE page intersects and stays fully judged, so a real clip or
   occlusion inside visible page content still fails.

   :path is the vector of child indices from the root ([] for the root
   itself) — a STRUCTURAL node identity that makes ancestry a prefix test
   (`related?`). Pairwise geometry rules need that: without it, a rule
   flags every button against its own label, going red for nesting rather
   than for the hazard it exists to catch. Identity cannot come from the
   node maps themselves, since two sibling nodes with identical geometry
   and no uid are equal as values.

   Public because it is the ONE tree walk the whole finding-producer
   registry shares: `devcards.findings` computes it once per card and
   hands it to every producer, so a consumer's rule never re-walks and
   never disagrees about what is hidden or snapped."
  ([root] (annotate-tree root nil [] {:hidden? false :snapped? false :content? false}))
  ([node parent path {:keys [hidden? snapped? content?]}]
   (let [child-hidden? (or hidden? (boolean (:hidden node)))
         tabview? (= "lv_tabview" (:type node))]
     (into [{:node node
             :parent parent
             :path path
             :hidden-under? hidden?
             :snapped-under? snapped?
             :tabview-content? content?}]
           (comp (map-indexed (fn [i child]
                                (annotate-tree child
                                               node
                                               (conj path i)
                                               {:hidden? child-hidden?
                                                :snapped? (or snapped?
                                                              (and content?
                                                                   (not (boxes-intersect?
                                                                         (:coords child)
                                                                         (:coords node)))))
                                                :content? (and tabview? (= 1 i))})))
                 cat)
           (:children node)))))

(defn related?
  "Are two annotated entries the same node, or is one an ancestor of the
   other? True iff one :path is a prefix of the other. Containment is how
   composition WORKS — a label inside a button overlaps it by
   construction — so pairwise geometry rules skip related pairs and judge
   only independently-placed elements."
  [a b]
  (let [pa (:path a)
        pb (:path b)]
    (when-not (and (vector? pa) (vector? pb))
      (throw (ex-info (str "related? needs annotated entries carrying :path "
                           "— build them with annotate-tree, never by hand")
                      {:a-path pa :b-path pb})))
    (let [n (min (count pa) (count pb))]
      (= (subvec pa 0 n) (subvec pb 0 n)))))

(defn- designed-flag?
  "Is `flag` on this annotated node one of the designed-geometry cases the
   ns docstring sanctions? Anything else is a live finding."
  [{:keys [node parent hidden-under? snapped-under? tabview-content?]} flag]
  (or (and (= flag :scrollable_overflow)
           (or (contains? designed-scroll-classes (:type node)) tabview-content?))
      ;; BOTH flags — drum-label-escape? is the one term proven for :offscreen:
      ;; its docstring covers a vertical translate past the box/DISPLAY edge,
      ;; which is the measurement obj_offscreen actually makes.
      (and (contains? #{:clipped :offscreen} flag)
           (drum-label-escape? node parent))
      ;; :clipped ONLY. Every term here is a statement about the PARENT's box —
      ;; a hidden child's self-derived geometry, and a page snapped out of the
      ;; carousel's content box — and neither says anything about the DISPLAY
      ;; rectangle, which is what obj_offscreen measures. The C side already
      ;; suppresses the snapped case via obj_in_scroll_region, so exempting it
      ;; here silenced nothing observable while resting on an argument that
      ;; never reached it.
      (and (= flag :clipped)
           (or (:hidden node) hidden-under? snapped-under?))
      (and (= flag :overflow) (roller-drum-overflow? node))))

(defn tree-findings
  "DOM findings for one card's parsed dump tree. `caps` declares what the
   loaded module can express: {:vis-px? bool}. Returns finding maps
   {:card :invariant :node :detail}.

   The 4-arity takes an ALREADY-ANNOTATED walk. The registry computes one
   walk per card and shares it with every producer; without this arity the
   built-in tree lane would re-walk internally, so 'computed once per card'
   would be false the moment that lane is armed."
  ([card-id root caps] (tree-findings card-id root caps nil))
  ([card-id root caps annotated]
   (when-not (map? root)
     (throw (ex-info "dump tree root must be a parsed map"
                     {:card card-id :got (type root)})))
   (let [annotated (or annotated (annotate-tree root))]
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
                    :detail "vis_px 0 — fully occluded or clipped away"})))))))

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

(def exemption-proof-keys
  "The PROOF an exemption owes, independent of what it matches. Both are
   mandatory non-blank strings — an exemption is a dated decision, and one
   without a `:retires-when` that can actually be observed is a permanent
   waiver wearing a temporary one's clothes."
  #{:rationale :retires-when})

(def exemption-match-keys
  "The axes an entry MATCHES on — the conjunct `exempt?` evaluates. :card and
   :invariant are mandatory. :node matches ANY node when absent.
   :act/outcome and :act/test-mode are optional too but narrow the OTHER
   WAY — absent reads the DEFAULT on both sides, so omitting one pins that
   axis to its default rather than widening the entry, which is what keeps
   an entry written before an axis existed matching exactly what it always
   matched and unable to start swallowing a newly-added :cantTell or
   :manual finding. :act/reason is NOT a free choice: `validate-exemptions!`
   REQUIRES it when :act/outcome names a reasoned outcome and REFUSES it
   otherwise, so no entry ever chooses to omit it. `exempt?` carries the
   full argument.

   The axes are read from `outcome/axis-keys` rather than re-spelled, so the
   entry side and the finding side cannot drift apart. A re-spelled list can
   omit an axis, and the omission is invisible from either side — an entry
   then silently matches on fewer axes than the findings carry, which is the
   hazard `exempt?` documents on the outcome and mode conjuncts."
  (into #{:card :invariant :node} outcome/axis-keys))

(def exemption-keys
  "Every key an exemption entry may carry: what it matches on, plus the proof
   it owes. The set stays closed so a typo is refused rather than silently
   widening what an entry swallows.

   PUBLIC because it is the ONE home of this list and two documents now read
   it rather than re-spelling it: `devcards.standard-brief` derives the
   generated STANDARD.md's exemption paragraph from it, and the
   ui-standard-review SKILL.md points at it by name. Re-spelling is how the
   mode went missing here in the first place; a generated brief that
   re-spelled it would rot the same way, silently."
  (into exemption-match-keys exemption-proof-keys))

(defn validate-exemptions!
  "Exemptions shape check — every entry {:card <string-or-regex-string>
   :invariant <kw> :rationale <ne-string> :retires-when <ne-string>} plus the
   optional :node <regex-string> / :act/outcome / :act/test-mode /
   :act/reason narrowing axes, no other keys. Throws on the first malformed
   entry; returns the list."
  [exemptions]
  (doseq [e exemptions]
    (when-not (map? e) (exemption-error e "not a map"))
    (when-let [extra (seq (remove exemption-keys (keys e)))]
      (exemption-error e (str "unknown keys " (vec extra))))
    (when-not (string? (:card e)) (exemption-error e ":card must be a string"))
    (when-not (keyword? (:invariant e)) (exemption-error e ":invariant must be a keyword"))
    ;; :node is a regex STRING, like :card, and the reason is not that
    ;; `re-pattern` would reject anything else — it returns a Pattern
    ;; unchanged, so a #"…" literal would match perfectly well. It is that
    ;; the exemption list is DATA: committed, reviewed, and round-tripped
    ;; through EDN, where a Pattern does not survive as itself. One spelling
    ;; for both regex fields is the second reason.
    (when (and (contains? e :node) (not (string? (:node e))))
      (exemption-error e (str ":node must be a regex STRING, like :card — "
                              "the exemption list is EDN data and a compiled "
                              "pattern does not round-trip")))
    (when-not (contains? outcome/outcomes (outcome/finding-outcome e))
      (exemption-error e (str ":act/outcome must be one of "
                              (vec (sort outcome/outcomes)))))
    (when (contains? outcome/unreportable-outcomes (outcome/finding-outcome e))
      (exemption-error e (str ":act/outcome " (outcome/finding-outcome e)
                              " can never appear on a finding, so an entry "
                              "naming it would be stale from birth")))
    (when-not (contains? outcome/test-modes (outcome/finding-mode e))
      (exemption-error e (str ":act/test-mode must be one of "
                              (vec (sort outcome/test-modes)))))
    ;; Mirrors the finding side exactly: an outcome that owes a reason owes
    ;; one here, and nothing else may carry one. An exemption for "we cannot
    ;; measure the bitmap gauges" must not also swallow "the mask emitter
    ;; failed".
    (if (contains? outcome/reasoned-outcomes (outcome/finding-outcome e))
      (when-not (keyword? (:act/reason e))
        (exemption-error e (str "an " (outcome/finding-outcome e)
                                " exemption owes an :act/reason keyword")))
      (when (contains? e :act/reason)
        (exemption-error e (str ":act/reason without an :act/outcome in "
                                (vec (sort outcome/reasoned-outcomes))))))
    (doseq [k [:rationale :retires-when]]
      (when-not (and (string? (get e k)) (not (str/blank? (get e k))))
        (exemption-error
         e
         (str k " must be a non-blank string — the proof " "is mandatory")))))
  exemptions)

(defn- exempt?
  "An exemption matches per (card, invariant, outcome, MODE, reason, node).

   The OUTCOME conjunct is load-bearing rather than tidy. Without it an entry
   written for a :cantTell — 'no glyph mask for this widget class; retires
   when the mask emitter lands' — silences the REAL :failed finding that
   appears on the same card and invariant the moment that emitter lands. The
   stale-exemption ratchet cannot catch that, because the entry is still
   matching: its own retirement condition is the very event that turns it
   into a defect-hider.

   THE MODE CONJUNCT IS THE SAME ARGUMENT ON THE ORTHOGONAL AXIS. What it
   guards is a lane armed through `devcards.findings` declaring
   `:test-mode :manual`, a mode the registry STAMPS onto that lane's
   findings. Without the conjunct, an entry written to disposition such a
   finding also swallowed the :automatic one sharing its (card, invariant,
   outcome, reason, node) — into :exempted, so the run was byte-identical to
   a clean one and the stale ratchet stayed quiet because the entry was still
   matching something.

   IT IS NOT WHAT SEPARATES THE VLM REVIEW FROM A DETERMINISTIC LANE HERE.
   That review is emitted BY HAND and passes through no producer, so BOTH
   sides read :automatic; what keeps them apart in this repo is the `:vlm/`
   namespace on :invariant, which the invariant conjunct already matches on.
   No protogen producer declares :manual, so this conjunct is armed ahead of
   the lane it is for — real machinery that nothing here exercises.

   The defaults point in DIFFERENT directions, and each preserves what
   absence has always meant. An absent :act/outcome or :act/test-mode is the
   default on BOTH sides — read through the SAME accessor, so those two
   sides cannot drift — meaning an entry written before these axes existed
   matches exactly the automatic/:failed findings it always matched, and
   cannot start swallowing a newly-added :cantTell or a :manual one. An
   absent :node matches ANY node, because that is exactly what an exemption
   does today.

   :act/reason has NO accessor and no default def — it is compared with a
   bare `=`, so nil-on-both-sides is arithmetic rather than policy. The
   effect is identical today and the coupling is what makes it safe:
   `validate-exemptions!` requires a reason exactly when :act/outcome names
   a reasoned outcome and refuses it otherwise, so an entry never chooses.
   Declaring a non-nil reason default would be INERT: the other two axes'
   defaults are live because their accessors read them, and this axis has no
   reader to reach. That asymmetry — not the default's value — is the seam to
   close first if a reason default is ever wanted."
  [exemption finding]
  (and (= (:invariant exemption) (:invariant finding))
       (= (outcome/finding-outcome exemption) (outcome/finding-outcome finding))
       (= (outcome/finding-mode exemption) (outcome/finding-mode finding))
       (= (:act/reason exemption) (:act/reason finding))
       (re-matches (re-pattern (:card exemption)) (str (:card finding)))
       (or (nil? (:node exemption))
           (boolean (re-matches (re-pattern (:node exemption))
                                (str (:node finding)))))))

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
