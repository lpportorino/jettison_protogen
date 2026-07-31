(ns devcards.invariants
  "Per-card DOM + emission invariants — the devcard gate's non-pixel lane.

   Pure: data in (parsed dump_tree JSON, captured host emissions, card meta),
   findings out (vector of finding maps). The caller renders; this judges.

   The invariant set (the corpus contract):
   - NO layout-defect flag on any node: text_truncated / text_clipped /
     text_wrapped / clipped / overflow / scrollable_overflow / offscreen /
     squished. A card exists to show a widget correctly; a defect flag is the
     renderer saying it could not. `text_wrapped` is the one no other flag can
     stand in for — a WRAP-mode label GROWS instead of clipping, so both other
     text flags stay absent while the reader gets a mid-word break.
   - EXCEPT where the card DECLARES the flag (`designed-flag-keys`,
     `apply-designed-flags`). The declaration is PER-FAMILY and its :kind
     selects its proof: a :subject entry (the flag is what the card renders)
     owes :rationale + :owner and no expiry, because no event retires it; a
     :false-positive entry (the CHECK is wrong here) owes the full waiver proof
     including :expires, because one does. Either way an entry matching nothing
     on a family it names is itself a HARD finding, so the list only shrinks.
   - NO zero-area node (coords collapse to w=0 or h=0).
   - NO zero-visible-area node (`vis_px` 0 = fully occluded/clipped away —
     the field is emitted only when visible < total, so 0 is the occlusion
     signal). Capability-gated: the caller declares :vis-px? from the module
     it actually loaded; the check never silently no-ops.
   - The dump is COMPLETE or fails as data: the renderer's overflow sentinel
     overwrites the tail of structurally cut JSON, and `devcards.host` detects
     that suffix BEFORE parsing and substitutes a root `truncated` key. The
     card is then unjudgeable — HARD :dump-truncated finding, never a parse
     throw or a skip.
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
     parent's extent (:overflow, :text_truncated, :text_clipped, :text_wrapped,
     :squished) stay judged; the SHOWN state is proven by the consumer's own
     driven-subject variants, not by this lane;
   - lv_tabview's content child is a scroll-snap page carousel: the content
     may carry :scrollable_overflow, and a page fully snapped outside the
     content box (plus its subtree) may carry :clipped / :zero-visible-area;
   - lv_roller's drum: vertical-only overflow of its options label is the
     wheel illusion (roller :overflow; label :clipped/:offscreen);
   - `designed-scroll-classes` (lv_table) may carry :scrollable_overflow.

   Exemptions are per-card + per-invariant, proof-carrying, EXPIRING,
   ratchet-down. `exemption-proof-keys` is the mandatory proof; an exemption
   that matches NO finding is itself a finding (:stale-exemption) so the list
   can only shrink, and one whose :expires has passed is a HARD failure so it
   cannot outlive its own decision. No entry is currently live in
   `lanes/gate-exemptions`, and the lv_line class this docstring once
   anticipated is gone rather than exempted: the renderer decodes LineProps
   now, so those cards draw a line inside their mandated finite w/h. Their zero
   findings are a real pass, not the vacuous one an empty box used to give.

   THAT IS A STATEMENT ABOUT THAT VECTOR, NOT ABOUT THE CORPUS. Concessions do
   live in `corpus/designed-flags.edn`, and its `:kind :false-positive` entries
   owe and are checked against the SAME four proof keys through the same
   `outcome/check-proof!`. They sit apart only because an exemption cannot match
   on theme FAMILY.

   AN EXEMPTION HERE IS THE UPSTREAM STANDARD'S \"WAIVER\"; the two words name
   one object and this ns does not rename the older one, because the word is
   spelled into `devcards.findings`, `devcards.outcome`,
   `devcards.standard-brief`, the generated STANDARD.md and CLAUDE.md, and a
   rename would be churn across every one of them for no change in what is
   checked. What the upstream demands of a waiver, and where each demand
   lands here:

     an OWNER              -> :owner       (NEW — nothing carried it before)
     an EXPIRY <= 90 days  -> :expires     (NEW — `waiver-horizon-days`)
     a CLAUSE ID           -> :invariant, which already IS one, and is
                              STRICTER than upstream's: the clause an entry
                              waives is the tuple (:card, :invariant, :node,
                              :act/outcome, :act/test-mode, :act/reason), so
                              a separate :clause-id would be a second name
                              for the same rule, free to disagree with it
     a DECISION-DOC SLUG   -> :rationale. Deliberately NOT its own key: this
                              repo has no decisions/ADR tree for a slug to
                              point AT, so the field could only ever be
                              checked for non-blankness — a clause that
                              cannot fail, which is the same defect as a
                              threshold key that silently falls back. The
                              obligation the slug carries (the decision is
                              recorded somewhere a reviewer can read it) is
                              discharged by :rationale being committed,
                              reviewed and diffable. The cost is real and
                              named: a consumer that DOES keep a decisions
                              tree must put the slug in :rationale prose
                              rather than in a field something could
                              resolve."
  (:require [clojure.string :as str]
            [devcards.outcome :as outcome])
  (:import (java.time LocalDate)
           (java.time.temporal ChronoUnit)))

(set! *warn-on-reflection* true)

(def defect-flags
  "Layout-defect keys the dump emits only when set (main.c dump_obj).

   `:text_wrapped` is the one that cannot be reached from the other two text
   flags: a WRAP-mode label GROWS instead of clipping, so `:text_clipped`
   (CLIP-mode only) and `:text_truncated` (dot_begin) both stay absent while
   the reader gets a mid-word break. It is also the only text flag that
   survives a theme change — growing needs no padding to go wrong — so it
   fires identically on every family."
  [:text_truncated :text_clipped :text_wrapped :clipped :overflow
   :scrollable_overflow :offscreen :squished])

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
   argument, never to silence a finding.

   IT MATCHES ZERO NODES IN THIS CORPUS, and that is worth stating rather than
   discovering. Measured across all six lv_table cards and all three theme
   families: none emits :overflow or :scrollable_overflow at all. The reason is
   in the cards, not in the argument above — `corpus/spec.edn` gives them no
   :w/:h on purpose, so LV_SIZE_CONTENT hugs the grid and there is nothing to
   scroll to. The class argument stays correct for a table that IS sized, which
   is why this is recorded rather than deleted: a consumer whose corpus sizes
   its tables exercises it immediately.

   WHAT THAT COSTS, said plainly: this is a hardcoded exemption with NO STALE
   CLAUSE. `corpus/designed-flags.edn` entries are punished when they stop
   matching; this set is not, so an entry here can outlive its justification
   silently — the one thing `gate-enforcement.md` §1 refuses of a concession,
   living somewhere the machinery cannot reach. Anything ADDED here inherits
   that, which is the real reason to extend it deliberately: prefer a per-card
   declaration, which does ratchet."
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
  ;; The tabview arm is the one that actually fires here: measured, 18 node
  ;; renders across the five lv_tabview cards plus the tabview kitchen sink, and
  ;; every one reports scroll_dirs "hor" — the page carousel scrolling
  ;; horizontally, which is exactly what it is for. An axis-scoped form of this
  ;; exemption was considered and is NOT warranted: the counter-case was built
  ;; (a tabview whose active page content is 900px tall) and the vertical
  ;; overflow landed on the PAGE node, which is not `tabview-content?` and is
  ;; already reported today. Claiming an axis would catch something here without
  ;; a case that reaches it is the unfounded kind of narrowing this file refuses.
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

;; ── Designed-flag declarations ──────────────────────────────────────────
;; A card whose SUBJECT is a defect flag — a label card named `wrap` that
;; exists to render wrapping, a textarea that scrolls because scrolling is
;; what a textarea does, a 40px box that documents a theme's padding floor —
;; needs a way to say so. Without one, the only dispositions are a WAIVER
;; (wrong: it expires, so a permanent property would be re-authored every 90
;; days forever, which is the "permanent waiver carrying a date" shape
;; `waiver-horizon-days` refuses) or widening the check (forbidden outright).

(def scroll-axis-spellings
  "`scroll_dirs` wire strings -> the AXIS SET each denotes.

   THREE STRINGS, TWO AXES, and the asymmetry is the whole reason this table
   exists rather than a keyword cast. \"both\" is a SPELLING, never a vocabulary
   word: a declaration that could name `:both` would carry a value no single
   axis ever equals, so it would be unmatchable on a hor-only or ver-only node
   and would read as a third axis that does not exist. Decoding to a SET makes
   the declaration and the observation the same kind of thing, which is what
   lets them be compared by equality.

   `dump_obj` emits `scroll_dirs` only beside `scrollable_overflow`, so a lookup
   miss here is the renderer contract being violated rather than a node with no
   axis — `scroll-axes` refuses instead of defaulting."
  {"hor" #{:hor} "ver" #{:ver} "both" #{:hor :ver}})

(def axis-bearing-flags
  "The defect flags whose findings carry `:axes`, and on which a declaration
   MUST name them. One entry today; a set rather than an `=` so a second
   axis-bearing key adds itself here and nowhere else."
  #{:scrollable_overflow})

(defn scroll-axes
  "The axis set for a node reporting `:scrollable_overflow`.

   THROWS on an absent or unrecognised `scroll_dirs`, and that is the point:
   the alternative is a default, and BOTH defaults are wrong in a way nothing
   downstream could see. Defaulting to every axis makes each declaration a
   blanket; defaulting to none makes every declaration stale. An absence here is
   an interpreter that emitted the flag without its axis, which is a contract
   violation and owes a loud failure rather than a guess."
  [node]
  (let [spelling (:scroll_dirs node)]
    (or (get scroll-axis-spellings spelling)
        (throw (ex-info (str "scrollable_overflow with no usable scroll_dirs: "
                             (pr-str spelling) " — dump_obj emits the axis beside"
                             " the flag, so this is a renderer contract"
                             " violation, not a node without an axis")
                        {:node (:type node) :scroll_dirs spelling
                         :known (vec (sort (keys scroll-axis-spellings)))})))))

(def designed-flag-keys
  "Every key a `:designed-flags` entry may carry. Closed, so a typo is refused
   rather than silently widening what the entry covers.

   :invariant and :families are MANDATORY and are what it matches on; :node
   matches ANY node when absent, exactly as `exemption-match-keys` does — the
   same axis words, so a reader who knows one knows the other.

   :families IS MANDATORY AND HAS NO 'ALL' SPELLING, deliberately. The whole
   value of this declaration over a waiver is that it is per-render: a card can
   be clean under the shipped theme and a documented floor under stock, and
   `#{1 2}` says exactly that while keeping family 0 enforced. An `:all` would
   let one keystroke excuse a family-0 regression on a card that was only ever
   meant to concede the others.

   :kind IS MANDATORY AND IT SELECTS THE PROOF, because two genuinely different
   dispositions were briefly conflated here and only one of them may skip an
   expiry:

     :subject         the flag IS what the card exists to render. A label card
                      named for wrapping wraps; a box sized to probe a padding
                      floor is clipped by that floor. There is no retiring
                      EVENT short of deleting the card, so :retires-when could
                      only be filled with a fiction and :expires would be
                      re-authored forever — which is the permanent-waiver-
                      carrying-a-date shape `waiver-horizon-days` refuses.
                      Proof: :rationale + :owner.

     :false-positive  the CHECK is wrong about this case. `:clipped` conflates
                      \"does not fit the content box\" with \"is drawn outside
                      and clipped\", and a widget whose label overruns a
                      stock-padded content box while staying inside its coords
                      trips the first while the render is complete. That has a
                      retiring event — the key being split into a fit verdict
                      and a visible-clip verdict — so it owes the FULL
                      `outcome/proof-keys`, checked by `outcome/check-proof!`,
                      exactly as a waiver does.

   Conflating them let entries whose own rationale said \"the check is wrong
   here\" ride the no-expiry path — review found them, and the corpus file is
   the enumeration, so no count is kept here to rot. The stale clause below is
   a real ratchet but it only bounds REMOVAL; it says nothing about a
   concession that should have carried an end date and did not.

   WHY THIS IS NOT ROUTED THROUGH `gate-exemptions`, which is the existing
   four-key waiver mechanism and would otherwise be the obvious home: its match
   axes are `exemption-match-keys`, and FAMILY is not one of them. An exemption
   therefore cannot say \"stock only\", so it would excuse the shipped family
   too — and the axis machinery's \"absent reads the DEFAULT on both sides\"
   rule is the wrong semantics to bolt family onto, since absent would then mean
   family 0 rather than any family. Giving the exemption axes a family is the
   better end state and is a change to `outcome/axis-keys` owing its own
   canaries; this is recorded as the reason rather than left as a silence.

   WHAT THE STALE CLAUSE DOES AND DOES NOT BOUND. It makes the list shrink: an
   entry matching nothing on a family it names is a HARD finding, so an entry
   cannot outlive the flag it declares, and unlike an expiry it cannot be
   satisfied by re-authoring a later date. It does NOT bound ADDITION — no
   ceiling, no watchlist tier — so a future author who breaks a widget under
   stock can add an entry and go green. The count of entries in force is printed
   with the run's other counts so that growth is at least visible; the committed
   file is the record of WHAT was absorbed, and every entry in it is proven
   still-live by the stale clause."
  #{:invariant :node :families :axes :kind :rationale :retires-when :owner
    :expires})

(def designed-flag-kinds
  "The two dispositions, and which proof each owes. See `designed-flag-keys`."
  #{:subject :false-positive})

(def designed-subject-proof-keys
  "The proof a `:kind :subject` entry owes: why this flag IS the card's subject,
   and who to ask. See `designed-flag-keys` for why the expiry pair is absent
   for this kind and MANDATORY for the other. Sorted, so a multi-defect entry
   always names the same key first.

   PUBLIC because `devcards.standard-brief` splices it into the generated
   STANDARD.md rather than typing the two keys — the same reason that file
   splices the waiver set: a derived list cannot silently disagree with the
   clause that enforces it, and a typed one can. There is deliberately no
   private twin of this vector; two literals of one list is the second
   silently divergent source this repo refuses everywhere else."
  [:owner :rationale])

(defn validate-designed-flags!
  "Assert every `:designed-flags` entry on one card is well-formed. Throws on
   the first problem, naming the card and the entry.

   REFUSES AN EMPTY ENTRY VECTOR for the reason the producer registry refuses
   an empty producer set: `[]` and \"this card declares nothing\" are the same
   value, so a card that MEANT to declare something and typo'd the key would
   read as a card that declared nothing — and its findings would then be live,
   which is the safe direction, but the author would get no signal at all. A
   card with nothing to declare omits the key."
  ([card-id entries] (validate-designed-flags! card-id entries (outcome/today)))
  ([card-id entries ^LocalDate now]
   (when (some? entries)
     (when-not (and (vector? entries) (seq entries))
       (throw (ex-info (str "card " (pr-str card-id)
                            ": :designed-flags must be a NON-EMPTY vector — omit"
                            " the key rather than declaring nothing")
                       {:card card-id :entries entries})))
     (doseq [e entries]
       (let [bad! #(throw (ex-info (str "card " (pr-str card-id)
                                        " :designed-flags entry: " %)
                                   {:card card-id :entry e}))]
         (when-not (map? e) (bad! "must be a map"))
         (when-let [extra (seq (remove designed-flag-keys (keys e)))]
           (bad! (str "unknown keys " (vec extra) " (closed shape); allowed "
                      (vec (sort designed-flag-keys)))))
         (when-not (contains? (set defect-flags) (:invariant e))
           (bad! (str ":invariant must be one of " (vec defect-flags)
                      " — a declaration for a clause that cannot fire is an"
                      " entry nothing will ever match")))
         (when-not (and (set? (:families e))
                        (seq (:families e))
                        (every? nat-int? (:families e)))
           (bad! ":families must be a non-empty set of theme-family numbers"))
         (when (and (contains? e :node) (not (string? (:node e))))
           (bad! ":node, when present, must be the node label string"))
         ;; :axes is MANDATORY-WHEN-APPLICABLE, never optional-with-a-default.
         ;; Both defaults are wrong and neither failure would be visible:
         ;; absent-reads-as-all-axes makes every entry the per-card blanket
         ;; `obj_overflow_dirs` refuses, and absent-reads-as-no-axes makes every
         ;; entry instantly stale. Requiring it is also what stops this field
         ;; landing INERT — an existing declaration for an axis-bearing flag
         ;; fails to LOAD until it names its axes, so the migration cannot be
         ;; skipped and then forgotten.
         (if (contains? axis-bearing-flags (:invariant e))
           (when-not (and (set? (:axes e))
                          (seq (:axes e))
                          (every? #{:hor :ver} (:axes e)))
             (bad! (str ":axes is REQUIRED for " (:invariant e)
                        " and must be a non-empty subset of #{:hor :ver}."
                        " There is no :both — that is a WIRE SPELLING for"
                        " #{:hor :ver}, and naming it would be an axis no node"
                        " can ever equal")))
           (when (contains? e :axes)
             (bad! (str ":axes is meaningless for " (:invariant e) " — only "
                        (vec (sort axis-bearing-flags)) " carry an axis, so an"
                        " entry naming one here could never match"))))
         (when-not (contains? designed-flag-kinds (:kind e))
           (bad! (str ":kind must be one of " (vec (sort designed-flag-kinds))
                      " — it selects which proof the entry owes, so it cannot"
                      " be omitted")))
         ;; The proof is a function of :kind, and the :false-positive arm is the
         ;; SAME `outcome/check-proof!` a waiver runs — one home, so the two acts
         ;; cannot drift about how long a concession may live.
         (if (= :false-positive (:kind e))
           (outcome/check-proof! e now bad! "designed-flag false positive")
           (doseq [k designed-subject-proof-keys]
             (when-not (and (string? (get e k)) (not (str/blank? (get e k))))
               (bad! (str k " must be a non-blank string — the proof a :subject"
                          " declaration owes is mandatory")))))
         ;; A :subject entry carrying an expiry is refused rather than ignored:
         ;; it means the author believed the concession ends, which is the
         ;; :false-positive disposition wearing the wrong label.
         (when (and (= :subject (:kind e))
                    (or (contains? e :expires) (contains? e :retires-when)))
           (bad! (str ":kind :subject must NOT carry :expires or :retires-when —"
                      " a card's subject has no retiring event, and naming one"
                      " means this is a :false-positive entry"))))))))

(defn- designed-entry-matches?
  "Does `entry` declare `finding` on `family`? Conjunctive over the axes the
   entry names; an absent :node matches any node.

   `:axes` COMPARES BY EQUALITY, never by subset or intersection, and each
   rejected reading fails in its own direction:
     - INTERSECTION would let `#{:ver}` absorb a node reporting BOTH, which is
       the horizontal regression the declaration was never shown.
     - SUBSET would let `#{:hor :ver}` be written once as a blanket that
       matches everything and that the stale clause can never punish.
   Equality makes an over-broad entry SELF-REFUTING through machinery that
   already exists: declare `#{:hor :ver}` on a card that only reports `ver` and
   it matches nothing, so the entry goes stale AND the underlying flag stays
   live. Both, loudly, with no new clause."
  [entry finding family]
  (and (= (:invariant entry) (:invariant finding))
       (contains? (:families entry) family)
       (or (not (contains? entry :node)) (= (:node entry) (:node finding)))
       (= (:axes entry) (:axes finding))))

(defn apply-designed-flags
  "Split `findings` against a card's `:designed-flags` declaration for the
   render at `family`.

   Returns {:live [..] :declared [..]}. `:live` is what the gate fails on;
   `:declared` is the record of what a declaration absorbed. Every entry that
   matched NOTHING contributes a HARD :stale-designed-flag finding to `:live`
   — that clause is what makes the list a ratchet instead of an accumulator,
   and it is the reason this declaration needs no expiry date.

   THE STALE CLAUSE IS SCOPED TO THE FAMILIES THE ENTRY NAMES. An entry
   declaring `#{1 2}` is judged stale only on a run that actually rendered
   family 1 or 2; asked about family 0 it is neither matched nor stale, because
   a run that never rendered its families has no evidence either way. Without
   that scoping, judging one family at a time would report every other
   family's entries stale on every card — findings that are pure artifacts of
   the caller's loop."
  ([card-id entries findings family]
   (apply-designed-flags card-id entries findings family (outcome/today)))
  ([card-id entries findings family ^LocalDate now]
   (validate-designed-flags! card-id entries now)
   (let [entries (or entries [])
         declared? (fn [f] (some #(designed-entry-matches? % f family) entries))
         {declared true live false} (group-by (comp boolean declared?) findings)
         in-scope (filter #(contains? (:families %) family) entries)
         matched (filter (fn [e] (some #(designed-entry-matches? e % family) findings))
                         in-scope)
         stale (remove (set matched) in-scope)]
     {:declared (vec declared)
      :live (into (vec live)
                  (for [e stale]
                    {:card card-id
                     :invariant :stale-designed-flag
                     :node (or (:node e) "(any)")
                     ;; NAMES WHAT WAS OBSERVED, because "the flag is gone" is
                     ;; a wrong diagnosis the moment :axes exists: the flag may
                     ;; be firing on a DIFFERENT axis, which is a narrowing to
                     ;; re-take rather than an entry to delete. A red that
                     ;; misnames its own clause is the failure this file
                     ;; refuses everywhere else.
                     :detail (let [seen (->> findings
                                             (filter #(= (:invariant e)
                                                         (:invariant %)))
                                             (keep :axes)
                                             distinct
                                             vec)]
                               (str "declares " (:invariant e)
                                    (when (:axes e)
                                      (str " axes "
                                           (pr-str (vec (sort (:axes e))))))
                                    " on family " family " and nothing matched"
                                    (if (seq seen)
                                      (str " — the flag fired with axes "
                                           (pr-str (mapv #(vec (sort %)) seen))
                                           ", so the entry is mis-scoped rather"
                                           " than obsolete")
                                      (str " — the flag it declares is gone, so"
                                           " the entry must go too"))))}))})))

(defn truncation-findings
  "The HARD :dump-truncated clause, on its own so every caller shares one copy.

   Truncation is not a question about what a card is FOR — it is the
   instrument reporting that it could not look. `devcards.host` substitutes a
   canonical `{:truncated true :children []}` root when it sees the renderer's
   overflow sentinel, so by the time any lane runs, EVERY key the tree carried
   is gone. A lane that routes on the card's purpose before checking this
   reports a card clean whose entire DOM was discarded — which is the
   'clean' / 'could not look' conflation this standard refuses everywhere.

   Callers: `tree-findings` (so the full lane keeps it) and
   `devcards.lanes/tree-producer` (BEFORE its `:expect` router, so the
   short-circuiting arms cannot skip it). Both must stay — the router bypasses
   `tree-findings` entirely for two of its arms."
  [card-id root]
  (when (:truncated root)
    [{:card card-id
      :invariant :dump-truncated
      :node "(root)"
      :detail "dump_tree overflowed its buffer — card unjudgeable"}]))

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
         (into (truncation-findings card-id root))
         ;; An axis-bearing flag carries its AXES onto the finding. Read here
         ;; rather than inside the declaration matcher on purpose: this is the
         ;; "what did the dump say" step, and `apply-designed-flags` stays a
         ;; step that judges findings without re-reading the tree.
         (into (for [entry annotated
                     flag defect-flags
                     :when (and (get (:node entry) flag) (not (designed-flag? entry flag)))
                     :let [axes (when (contains? axis-bearing-flags flag)
                                  (scroll-axes (:node entry)))]]
                 (cond-> {:card card-id
                          :invariant flag
                          :node (node-label (:node entry))
                          :detail (str "layout defect flag " (name flag)
                                       (when axes
                                         (str " on " (pr-str (vec (sort axes))))))}
                   axes (assoc :axes axes))))
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

(def waiver-horizon-days
  "The OUTER BOUND on a waiver's life, in days. An entry whose `:expires` is
   further out than this is refused at write time.

   The horizon is a separate clause from expiry and catches the opposite
   mistake: expiry catches a waiver that OUTLIVED its decision, the horizon
   catches one written never to lapse in the first place. Without it,
   `:expires \"2099-01-01\"` satisfies every other clause here and is a
   permanent waiver carrying a date — precisely the shape `:retires-when`
   prose already was.

   Measured against the clock rather than against a write date, deliberately:
   nothing records when an entry was authored, and a stored write date would
   be a second fact free to disagree with git. `expires <= today + 90` is
   stable under the passage of time — the gap only shrinks — so an entry that
   passed on the day it was written can never start failing THIS clause
   later. Only the expiry clause fires with time, which is what expiry is.

   THE VALUE IS `outcome/horizon-days`, read rather than re-spelled, and it
   moved there because it is not a waiver-only number any more: a GLOBAL
   policy deviation runs on the same horizon (`outcome/validate-policy!`).
   Two constants named for the two acts would be free to disagree about how
   long a concession may live — the exact class of drift that let the global
   act owe less proof than the scoped one. The NAME stays here because
   `devcards.standard-brief` and the generated STANDARD.md splice it."
  outcome/horizon-days)

(defn today
  "The gate's clock: the current date in UTC.

   UTC RATHER THAN THE DEFAULT ZONE, and this is not fussiness. protogen is
   the pinned upstream for a 10+ repo fleet whose CI runners and operators sit
   in unknown zones; with a local-zone clock the same waiver is expired in one
   checkout and live in another for up to a day, and the two disagree about
   whether the gate is red. One day boundary for the whole fleet.

   Callers pass a date explicitly wherever the answer must be reproducible —
   `validate-exemptions!` and `apply-exemptions` both take one — so this is
   the DEFAULT and never the only source.

   DELEGATES TO `outcome/today` for the reason `waiver-horizon-days` carries:
   the policy validator reads a clock now too, and two clocks would expire a
   waiver and a policy deviation on different mornings."
  ^LocalDate []
  (outcome/today))

(def exemption-proof-keys
  "The PROOF an exemption owes, independent of what it matches. All four are
   mandatory non-blank strings. THE SET IS `outcome/proof-keys`, read rather
   than re-spelled.

   IT WAS NEVER EXEMPTION-SPECIFIC, and the move records that. The GLOBAL
   policy deviation owes the same four, through the same `check-proof!`; a
   proof set living in the namespace of ONE of the two acts is a proof set the
   other can quietly diverge from, and divergence is exactly what happened —
   the scoped waiver gained an :owner and an :expires while the global
   deviation kept two prose strings. The NAME stays here because
   `devcards.standard-brief` and the ui-standard-review SKILL.md read it.

   :rationale and :retires-when are the argument: why this finding is not a
   defect, and what event makes the entry unnecessary. :owner and :expires
   are the ACCOUNTABILITY, and they are new — an entry carrying only the
   first two was a decision with no author and no end.

   :retires-when SURVIVES the arrival of :expires, and merging them would
   lose the half that matters. The retirement CONDITION is a real-world event
   ('the mask emitter lands') that no machine here can evaluate; the DATE is
   the outer bound at which the decision must be re-taken whether or not that
   event happened. Keeping only the date tells you WHEN to look and not WHAT
   for; keeping only the prose is what let an exemption be permanent while
   claiming otherwise. Both, or neither is worth having.

   WHAT :owner CANNOT SEE, said out loud so no pass message over-claims: the
   check is `non-blank string`. `:owner \"TODO\"` passes. What it buys is not
   verification, it is that the expiry failure below has a name in it to
   route to; an unfalsifiable allowlist of real humans would be a second
   register free to rot against the team."
  outcome/proof-keys)

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
  "Exemptions shape check — every entry carries the four
   `exemption-proof-keys` plus :card <regex-string> and :invariant <kw>, and
   may narrow with :node <regex-string> / :act/outcome / :act/test-mode /
   :act/reason. No other keys. Throws on the first malformed entry; returns
   the list.

   `now` is the date the expiry clauses are judged against; the 1-arity reads
   `today` (UTC). IT IS A PARAMETER RATHER THAN A LOOKUP because this fn is
   otherwise pure and its callers are gates: a test that could not pin the
   date would have to write assertions against a moving answer, which is how
   an expiry canary quietly stops being able to go red.

   THIS IS THE ONE PLACE A DEVCARD GATE READS A CLOCK, and the cost is real
   enough to state rather than bury: a battery that passed yesterday can fail
   today with no commit in between. That is what an expiry IS — the forcing
   function is worthless if it can only fire when someone happens to edit the
   file — but it is also the class of red most likely to be waved through as
   'flaky'. Two things keep it honest. The impurity reaches ONLY the
   human-authored exemption list: no pixel, no golden, no generated text is a
   function of this date, so nothing byte-reproducible loses that property.
   And the message names the owner and the lapse, so the red routes itself
   instead of needing a triage."
  ([exemptions] (validate-exemptions! exemptions (today)))
  ([exemptions ^LocalDate now]
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
     ;; The four proof keys and the two expiry clauses, through
     ;; `outcome/check-proof!` — the SAME call the global policy deviation
     ;; makes, with "waiver" as the noun its messages read with. They were
     ;; spelled here until the deviation was brought up to the same four keys;
     ;; leaving a second copy behind would have recreated, inside one release,
     ;; the drift the move exists to close.
     (outcome/check-proof! e now #(exemption-error e %) "waiver"))
   exemptions))

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

(defn waiver-summary
  "The one-line waiver census a run header prints beside its violation count:
   how many waivers are in force and when the next one lapses.

   NOTHING IN THIS REPO CALLS IT, and saying so is the point — a primitive
   that looks wired and is not is worse than one that is plainly not. The
   wiring is one line wherever a run prints its header
   (`devcards.lanes` / `devcards.core`, neither of which this change owns),
   and it belongs there rather than here because this ns is pure and a header
   is I/O.

   WHY IT EXISTS AT ALL RATHER THAN BEING RE-DERIVED AT THE CALL SITE: the
   count a reader needs beside `N violations` is the number of ENTRIES in
   force, which is not `(count (:exempted …))` — that counts FINDINGS waived,
   and one entry can waive many findings or, while stale, none. Two numbers
   one keystroke apart, and the wrong one under-reports the debt exactly when
   a single broad entry is doing the most work. One home for the arithmetic
   is how they stop being confusable.

   TAKES AN ALREADY-VALIDATED LIST and parses each `:expires` STRICTLY: an
   unparseable date throws out of here rather than being skipped. Skipping was
   the first cut and it is the shape this repo refuses everywhere else — the
   count would stay right while the lapse date silently became some LATER
   entry's, so the census would under-report exactly the number it exists to
   report, and no branch of it could ever fail. A list reaching here with a
   bad date has bypassed `validate-exemptions!`, which is a caller defect and
   should be loud.

   `now` defaults to `today` (UTC), as everywhere else here."
  (^String [exemptions] (waiver-summary exemptions (today)))
  (^String [exemptions ^LocalDate now]
   (if (empty? exemptions)
     "0 waivers"
     (let [next-up (->> exemptions
                        (map #(LocalDate/parse ^String (:expires %)))
                        sort
                        first)]
       (str (count exemptions) " waiver" (when (not= 1 (count exemptions)) "s")
            ", next lapses " next-up " ("
            (outcome/plural-days (.between ChronoUnit/DAYS now ^LocalDate next-up))
            ")")))))

(defn apply-exemptions
  "Split findings against the proof-carrying exemption list. Returns
   {:live [..] :exempted [..] :stale-exemptions [..]} — an exemption that
   matched nothing is STALE and reported as its own finding class, so the
   list ratchets down by construction.

   `now` is handed straight to `validate-exemptions!`; the 2-arity reads
   `today` (UTC), which is what every caller in this repo does."
  ([findings exemptions] (apply-exemptions findings exemptions (today)))
  ([findings exemptions ^LocalDate now]
   (let [exemptions (validate-exemptions! exemptions now)
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
                              stale)})))
