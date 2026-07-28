(ns devcards.cascade
  "THE NAME IS A MISNOMER AND THE FIRST THING THIS FILE OWES IS THE
   RETRACTION. There is no style cascade left to walk. `dump_obj`
   (renderer/src/main.c) resolves style on the C side — through LVGL's OWN
   getters, per PART, at the object's CURRENT state, with the accumulated
   `recolor` applied (`style_color_drawn`) and LVGL's opacity RAILS folded
   (`obj_effective_opa`) — and emits the answer. A consumer asking \"which
   declared style entry wins for this node, part and state\" is asking a
   question the interpreter already answered; re-deriving it here would be a
   second, drifting model of the draw path, which is the thing this repo bans
   everywhere else.

   What is left is small, and it is NOT a cascade:

     1. ONE INHERITANCE WALK. `text_color` is emitted only where it CHANGES
        from the parent's, so a node without the key wears the nearest
        self-or-ancestor that has it. Measured on the shipped corpus, 1114 of
        2606 nodes carry no `text_color` — so a reader that treats absence as
        \"no foreground\" loses the foreground on nearly half the tree, and one
        that substitutes a default is confidently wrong. Every one of the 492
        screen roots emits it, which is what makes the walk terminate.

     2. A BACKDROP THAT MOSTLY CANNOT BE DETERMINED, and saying so is this
        file's real product. `dump_obj` declines the ancestor walk on purpose
        and its block comment gives the reason: PARTS ARE NOT NODES, so a
        buttonmatrix's per-item fill — a covering rect every caption rides on —
        exists in no node any walk from the buttonmatrix could reach. Measured:
        every one of the 356 non-blank `lv_label` nodes in the corpus has NO
        `bg_color` of its own and carries `backdrop_unresolved`. For the
        dominant text population the honest answer to \"on what?\" is UNKNOWN,
        and it is unknown for an architectural reason a resolver cannot fix.

   So this namespace is an ACCOUNTING instrument, not an arithmetic one. It
   turns the dump's per-key absence conventions into ONE three-answer verdict
   per node, so that ten consumers do not each re-derive them and get a
   different subset right. Reproduce every count above with the census probe
   named at the bottom of this docstring rather than trusting it.

   ── WHAT IT RETURNS ────────────────────────────────────────────────────
   `resolve-tree` gives one entry per node, depth-first in the same order (and
   with the same `:path` convention) as `invariants/annotate-tree`, so a caller
   can zip the two. Each entry carries TWO axes, deliberately not fused:

     :glyphs   :yes | :no | :unknown — does this node put glyphs on screen?
     :outcome  :resolved | :no-glyphs | :unknown — is the PAIR determined?

   plus `:reasons` (a sorted vector, non-empty exactly when `:outcome` is
   `:unknown`, every entry a key of `reasons` below), and `:fg` / `:bg`
   whenever that END is determined even if the other is not — an entry can be
   UNKNOWN and still hand a caller a bound.

   `:bg` is a property of the node and is reported whatever it draws; `:fg` is
   reported only where there ARE glyphs to colour, because `text_color`
   resolves on every object and on a switch or a slider it colours nothing.
   Handing that back as a foreground is §6.9's over-coverage half — a pair
   that cannot occur, scored anyway.

   `:no-glyphs` is a POSITIVE determination and `:unknown` is \"I could not
   look\". A resolver with only two answers reports those as the same thing,
   which is the single failure this codebase refuses.

   ── WHICH WAY EACH KEY'S ABSENCE FAILS ─────────────────────────────────
   Read from `dump_obj`'s own vocabulary block, which is its one home. They do
   NOT all fail the same way, and this list is why the code below reads what it
   reads:

     text_color   absent => THE NEAREST ANCESTOR THAT EMITTED ONE. Never a
                  default, never \"none\".
     text_opa     absent => COVER, and present it ALREADY INCLUDES the
                  whole-widget `opa`. Never multiply the two — which is also
                  why nothing here walks `opa`: `obj_effective_opa` folded the
                  whole chain before either key was emitted.
     bg_color     absent => THIS NODE PAINTS NO MAIN FILL. It is NOT \"the
                  default background\"; nothing was drawn.
     bg_opa       absent WITH bg_color => the fill covers fully.
     text_on      absent => this node's own glyphs, if any, ride on MAIN.
                  Present => the class draws its primary text on another PART
                  and that part's fill is what is under them. `text_on.color`
                  is emitted unconditionally; `text_on.bg` follows the
                  bg_color convention, and its absence means that part paints
                  no fill.
     backdrop_unresolved
                  present => the interpreter positively declares the fill under
                  this node's glyphs does not cover. Absent => NOT \"resolved\".
                  See the next section.

   ── WHY THE BACKDROP IS DERIVED AND NOT READ OFF `backdrop_unresolved` ──
   That key is gated on `obj_draws_text`, which decides glyph-bearing-ness with
   `lv_obj_check_type` — an EXACT class-pointer comparison (lv_obj.c:428) —
   for lv_label, lv_checkbox and lv_dropdown, and with `obj_text_part` for
   buttonmatrix, table, roller and scale. Four classes this renderer emits draw
   glyphs and match NEITHER test: `lv_roller_label` (base `lv_label`, so the
   exact check fails), `lv_dropdown-list`, `lv_textarea` and its `lv_spinbox`
   subclass. For those, `backdrop_unresolved` is silent whatever the fill does.

   Measured, the hazard is live rather than theoretical: all 40 `lv_roller_label`
   nodes in the corpus emit no `text`, no `bg_color`, no `text_on` and no
   `backdrop_unresolved`. Those four keys are the dump's ENTIRE text-and-style
   vocabulary, so a node drawing the roller's unbanded option rows is
   indistinguishable — in every key that bears on this question — from one that
   draws nothing at all. (It still carries geometry, `vis_px` and `clickable`;
   none of those says anything about ink or fill.) A resolver keying on the flag
   calls that population resolved, with no background at all.

   So the backdrop is derived POSITIVELY from the fill keys instead, which is
   exact and independent of `obj_draws_text`: the fill under a node's glyphs
   covers IFF its colour key is present AND its opa key is absent (or 255).
   `backdrop_unresolved` is then used only as a CONSISTENCY CHECK in the one
   direction that must hold — present implies the fill does not cover — and a
   violation THROWS, because it would mean this file's model of the C has
   drifted from the C.

   ── THE ONE DISAGREEMENT THAT IS NOT A BUG, RECORDED RATHER THAN HIDDEN ─
   The interpreter's flag and `devcards.opa`'s class sets do not partition the
   same population, and `lv_table` is where they part: `obj_text_part` matches
   it by class, so 6 corpus nodes carry `backdrop_unresolved`, while
   `opa/text-free-classes` lists it because `renderer.c` never calls
   `lv_table_set_cell_value` and every cell therefore renders EMPTY. On this
   corpus opa is right and the flag over-reports — a finding with no subject.
   This file keeps opa's verdict (it is the repo's ONE home for the question,
   derived from the LVGL draw path with the three-route check) and records the
   disagreement as `:interpreter-claims-glyphs?` rather than dropping it.

   DEFERRING TO opa THERE IS ONLY SAFE BECAUSE ITS CONDITION IS GATED, which is
   the part worth naming: `opa-test/table-carve-out-still-holds` strips comments
   from `renderer.c` and fails the moment a real `lv_table_set_cell_value` call
   appears — verified, the string occurs in this tree only inside that file's
   own warning comment. So the day cells get text, that gate reds, `lv_table`
   moves to `glyph-classes`, and these 6 nodes become `:unknown` here with no
   edit to this file. Without that gate the deferral would be a silent
   ratchet.

   ── WHAT THIS DELIBERATELY DOES NOT DO ─────────────────────────────────
   - NO CONTRAST RATIO, and no verdict. `docs/UI-QUALITY-CONTRACTS.md` §0 is
     explicit that no readability lane ships here, and §6.9 shows why a pair
     alone is not yet a gate. This returns the pair and its provenance; what a
     floor is, and whether one is even the right instrument, is not settled
     here.
   - NO PRODUCER ENTRY. Nothing here arms a lane, and `devcards.lanes` is not
     this file's to edit — so a producer would be a registry entry no lane
     runs. `devcards.findings` names that hazard one level down, about producer
     KEYS (\"an entry carrying a knob nothing reads looks armed and does
     nothing\"); an unarmed producer is the same hazard one level up, and this
     file declines to ship one rather than ship it dormant. If a lane ever
     wants it, `reasons` below is already in the producer `:reasons` shape (a
     closed keyword -> non-blank-doc map) and the three-way outcome maps onto
     `:cantTell`.
   - NO ANCESTOR WALK FOR THE BACKDROP. See above — it is refuted, not omitted.
   - NO HIDDEN/CLIPPED FILTERING. `annotate-tree` is the ONE shared walk that
     owns `:hidden-under?` and the carousel-snap exclusions; re-deriving them
     here would be the fork this repo bans, so join on `:path` instead. And
     note `devcards.opa`'s reasoning before filtering on the result: a faded or
     unreadable subtree that is merely hidden TODAY is an authoring defect the
     moment it is shown, so dropping hidden nodes from a readability question
     answers a different question.
   - NO COMPOSITE ARITHMETIC. When ink or fill is below COVER the drawn value
     is a blend whose exact byte depends on the software blend path taken
     (§6.9); this reports UNKNOWN and hands back both ends rather than
     computing a number the renderer would not agree with.

   Colours come back LOWER-CASE `#rrggbb` — the dump's own spelling — so a
   caller comparing against a token table normalises one side, not both. A
   malformed colour THROWS rather than propagating.

   Census (in the toolchain container, from tools/devcards/):
     clojure -Sdeps '{:aliases {:probe {:extra-paths [\"../../.fork-scratch\"]}}}' \\
       -M:bindings:probe -m cascade-census"
  (:require [clojure.string :as str]
            [devcards.opa :as opa]))

(set! *warn-on-reflection* true)

(def cover
  "LV_OPA_COVER. The value every absent opa key stands for, and the only
   value at which a fill or an ink determines what is drawn on its own."
  255)

(def label-class
  "The one class whose glyph-bearing-ness is read off the NODE rather than
   looked up: `dump_obj` emits `text` for exactly `lv_label`
   (`lv_obj_check_type`, class-pointer equality), so non-blank text means
   glyphs and empty text means none. Kept in step with `devcards.opa`, which
   makes the same call on the same ground and therefore lists it in none of
   its class sets — `cascade-test` pins that."
  "lv_label")

(def reasons
  "The CLOSED vocabulary of why a pair is not determined, keyword -> what
   would close it. Closed, and shaped like a producer's `:reasons` map, for
   the reason `devcards.findings` gives: an unenumerated reason vocabulary is
   an open one, and an open one cannot be reviewed. 'The class was never
   classified', 'the glyphs sit on something this node does not name' and 'the
   ink is translucent so the drawn colour is a blend' are three different
   non-answers, and one that will not say which it is has re-fused them.

   `cascade-test` asserts every key here is REACHABLE — a declared reason no
   input can produce is a vocabulary lie, and it is the failure mode a closed
   set acquires as the code under it changes."
  {:class-not-declared
   (str "the node's class appears in none of devcards.opa's sets, so whether "
        "it puts glyphs on screen has never been decided — declare it in "
        "`:glyph-classes` or `:text-free-classes`")
   :glyph-source-unserialised
   (str "the class can draw glyphs, but whether THIS instance does depends on "
        "a value dump_tree does not carry (an lv_image's SYMBOL src)")
   :text-color-unrooted
   (str "neither this node nor any ancestor in the supplied tree emitted "
        "`text_color`, so the inherited foreground has no source here — the "
        "shape a truncated dump or a detached subtree arrives in")
   :ink-not-opaque
   (str "the glyph alpha is below LV_OPA_COVER, so what is drawn is a blend "
        "of the styled colour and whatever is behind it; both ends are "
        "reported, the blend is not computed")
   :backdrop-unresolved
   (str "the fill under this node's glyphs does not fully cover, and the "
        "interpreter declines the ancestor walk that would name what is "
        "behind it because PARTS ARE NOT NODES (dump_obj's own block comment)")})

(defn- colour!
  "A dump colour as lower-case `#rrggbb`, or a throw naming the key. A
   resolver that passes a malformed colour through produces a pair whose
   wrongness surfaces at whatever consumes it, arbitrarily far away."
  [v where]
  (if (and (string? v) (re-matches #"#[0-9a-fA-F]{6}" v))
    (str/lower-case v)
    (throw (ex-info (str "dump key " where " is not a #rrggbb colour: "
                         (pr-str v))
                    {:key where :value v}))))

(defn- alpha!
  "An opa value from the dump, defaulted to `cover` when ABSENT — which is
   the convention, never a guess. Out of range throws."
  [m k]
  (let [v (get m k cover)]
    (if (and (integer? v) (<= 0 v 255))
      v
      (throw (ex-info (str "dump key " k " is not an opacity in 0..255: "
                           (pr-str v))
                      {:key k :value v})))))

(defn- fill
  "The fill a (colour, opa) key PAIR describes, or nil when the node paints
   none — which is what an absent colour key means, and it does NOT mean 'the
   default background'.

   `:covers?` is derived from the VALUE rather than from the key's presence.
   The two agree on every real dump (the opa key is emitted only when it
   differs from COVER), and deriving it from the value additionally makes a
   hand-written `{:bg_color .. :bg_opa 255}` behave arithmetically instead of
   as a contradiction."
  [m colour-key opa-key from]
  (when (contains? m colour-key)
    (let [o (alpha! m opa-key)]
      {:hex (colour! (get m colour-key) colour-key)
       :opa o
       :covers? (= cover o)
       :from from})))

(defn- class-set
  "The shipped set with the consumer's additions unioned on. ADDITIVE, for
   the reason `devcards.opa/class-set` gives: a deletion is how this would get
   quietly relaxed for the classes it was written for."
  [shipped supplied]
  (into shipped (or supplied #{})))

(defn- glyph-verdict
  "Does this node put glyphs on screen? `:yes`, `:no` or `:unknown`.

   Keyed on `devcards.opa`'s class sets rather than on a list of this file's
   own, because that namespace is the repo's ONE home for the question and
   derives its rows from the `lv_obj_init_draw_label_dsc` call sites plus the
   two routes a grep for them misses. A second list here would fork silently."
  [node glyphs text-free]
  (let [t (:type node)]
    (cond
      (= label-class t)
      (if (contains? node :text)
        (if (str/blank? (str (:text node))) :no :yes)
        (throw (ex-info (str "an " label-class " node carries no `text` key — "
                             "dump_obj emits it unconditionally for exactly "
                             "this class, so its absence means the tree did "
                             "not come from this interpreter and the node's "
                             "glyph-bearing-ness cannot be read off it")
                        {:node (dissoc node :children)})))
      (contains? glyphs t) :yes
      (contains? text-free t) :no
      :else :unknown)))

(defn- entry
  "One node's verdict. `inherited-hex` is the foreground in force from the
   nearest ancestor that emitted `text_color`, or nil when none did."
  [node path inherited-hex glyphs text-free]
  (let [t (:type node)
        verdict (glyph-verdict node glyphs text-free)
        ton (:text_on node)
        _ (when (and (some? ton) (not (contains? ton :color)))
            (throw (ex-info (str "a `text_on` object carries no `color` — "
                                 "dump_obj emits that member unconditionally "
                                 "whenever it emits text_on")
                            {:node (dissoc node :children)})))
        declared (contains? node :backdrop_unresolved)
        owes-pair? (not= :no verdict)
        ;; THE INK. text_on takes over the node's OWN glyphs when present; the
        ;; MAIN `text_color` still feeds the inheritance chain for CHILDREN,
        ;; which is what the walk below threads and is why the two are not the
        ;; same read. (`lv_roller` is the live case: its MAIN text colour is
        ;; what its child `lv_roller_label` wears, while its own selected-row
        ;; glyphs are `text_on`'s.)
        ;;
        ;; REPORTED ONLY WHERE THERE ARE GLYPHS TO COLOUR. `text_color`
        ;; resolves on EVERY object — a switch, a slider, a bar all carry one
        ;; — and on a node that puts no glyph on screen it colours NOTHING.
        ;; Handing it back as a foreground is `docs/UI-QUALITY-CONTRACTS.md`
        ;; §6.9's over-coverage half: a pair that cannot occur, scored anyway
        ;; and read as a catastrophic failure. The colour still reaches the
        ;; CHILDREN, because the walk threads it independently of this.
        fg (when owes-pair?
             (if (some? ton)
               (let [o (alpha! ton :text_opa)]
                 {:hex (colour! (:color ton) :text_on.color)
                  :opa o :covers? (= cover o) :from :text-on})
               (when inherited-hex
                 (let [o (alpha! node :text_opa)]
                   {:hex inherited-hex :opa o :covers? (= cover o)
                    :from (if (contains? node :text_color) :self :inherited)}))))
        bg (if (some? ton)
             (fill ton :bg :bg_opa :text-on-bg)
             (fill node :bg_color :bg_opa :bg-color))]
    ;; THE MODEL CHECK, in the one direction that is a statement about the C's
    ;; arithmetic alone: `backdrop_unresolved` is emitted iff the glyph fill is
    ;; below COVER and the node draws text, so the flag and a covering fill
    ;; cannot both be true. If they are, this file's reading of dump_obj has
    ;; drifted from dump_obj — silently picking one answer is the failure the
    ;; whole namespace exists to refuse.
    (when (and declared bg (:covers? bg))
      (throw (ex-info (str "node declares `backdrop_unresolved` while its "
                           "glyph fill fully covers — dump_obj cannot emit "
                           "both, so this resolver's model of the emission "
                           "conditions is stale")
                      {:type t :path path :bg bg})))
    (let [rs (cond-> []
               (= :unknown verdict)
               (conj (if (contains? opa/ambiguous-classes t)
                       :glyph-source-unserialised
                       :class-not-declared))
               (and owes-pair? (nil? fg)) (conj :text-color-unrooted)
               (and owes-pair? (some? fg) (not (:covers? fg)))
               (conj :ink-not-opaque)
               (and owes-pair? (not (and (some? bg) (:covers? bg))))
               (conj :backdrop-unresolved))
          rs (vec (sort rs))]
      (cond-> {:path path
               :type t
               :glyphs verdict
               :outcome (cond (= :no verdict) :no-glyphs
                              (seq rs) :unknown
                              :else :resolved)
               :reasons rs}
        (some? fg) (assoc :fg fg)
        (some? bg) (assoc :bg bg)
        ;; Recorded only where it ADDS to or contradicts the class verdict —
        ;; on a :yes node it merely restates it. `lv_table` is the measured
        ;; contradiction; see the ns docstring.
        (and declared (not= :yes verdict))
        (assoc :interpreter-claims-glyphs? true)))))

(defn- walk
  [node path inherited-hex glyphs text-free]
  (let [self-hex (when (contains? node :text_color)
                   (colour! (:text_color node) :text_color))
        in-force (or self-hex inherited-hex)]
    (into [(entry node path in-force glyphs text-free)]
          (comp (map-indexed
                 (fn [i child]
                   (walk child (conj path i) in-force glyphs text-free)))
                cat)
          (:children node))))

(defn resolve-tree
  "Every node of a parsed `dump_tree` root, with the effective (foreground,
   background) pair it draws — or an explicit UNKNOWN saying which end could
   not be determined and why.

   Returns a vector, depth-first, `:path` being the vector of child indices
   from the root exactly as `invariants/annotate-tree` builds it, so the two
   walks zip.

   `opts` may carry `:glyph-classes` and `:text-free-classes`, consumer widget
   classes UNIONED onto `devcards.opa`'s shipped sets — the same additive
   contract that producer's thresholds declare, so a consumer classifies its
   own widgets without forking either list. A class declared in both is
   refused: the verdict would be an accident of ordering.

   Throws rather than guessing on a malformed colour, an out-of-range opacity,
   an `lv_label` with no `text`, a `text_on` with no `color`, and on a
   `backdrop_unresolved` that contradicts the fill keys."
  ([root] (resolve-tree root nil))
  ([root {:keys [glyph-classes text-free-classes]}]
   (when-not (map? root)
     (throw (ex-info (str "resolve-tree needs a parsed dump_tree root map, got "
                          (pr-str (type root)))
                     {:root root})))
   (let [glyphs (class-set opa/glyph-classes glyph-classes)
         text-free (class-set opa/text-free-classes text-free-classes)]
     (when-let [both (seq (sort (filter glyphs text-free)))]
       (throw (ex-info (str "class(es) " (vec both) " declared BOTH "
                            "glyph-bearing and text-free — the pair would be "
                            "owed and not owed for the same node, and "
                            "whichever arm won would be an accident of "
                            "ordering")
                       {:classes (vec both)})))
     (walk root [] nil glyphs text-free))))
