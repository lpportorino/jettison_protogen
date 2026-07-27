(ns devcards.opa
  "THE OPACITY BAN — no whole-widget fade over anything that puts glyphs on
   screen. `docs/UI-QUALITY-CONTRACTS.md` §6 is the contract; this is its
   deterministic clause.

   WHY IT IS A RULE AND NOT FOUR FIXES. The theme had already worked around
   this hazard three separate times without writing it down — `disabled`
   declining opa, `disabled_fill` neutralising the stock recolor,
   `disabled_flat` using the fade deliberately for line-art — while four
   glyph-bearing classes still carried it. A hazard handled case-by-case three
   times and missed four times is a rule waiting to be written, and it will be
   forgotten again.

   ── THE MECHANISM, so a consumer can recognise it in a widget we have
   never seen ────────────────────────────────────────────────────────────
   `lv_obj_refr` (lv_refr.c) folds a MAIN `opa` into `layer->opa`
   (`LV_OPA_MIX2`) and a MAIN `recolor` into `layer->recolor`
   (`lv_obj_style_apply_recolor`) BEFORE it redraws the object, and restores
   both afterwards — so both accumulate down the whole subtree. LVGL then has
   NO per-run text exemption: `lv_obj_init_draw_label_dsc` reads the same
   `get_layer_opa` every rect/line/arc/image dsc reads and mixes it into the
   GLYPH alpha, and `normal_apply_layer_recolor` is applied to bg, border,
   outline, shadow AND text colour alike (all lv_obj_draw.c). So a fade or a
   recolor moves the glyph and the fill it sits on TOWARD EACH OTHER, and the
   pair the operator reads is not the pair the tokens declare.

   That is what this clause protects, and it is why it gates the palette
   derivation rather than merely tidying the theme: with neither mechanism
   live, the AUTHORED pair IS the RENDERED pair, so a token-level contrast
   gate can mean something. Under a fade it cannot — the rendered colours
   appear in no token table at all. Measured before the ban: a themed button
   under LV_STATE_DISABLED rendered #ACACAC on #6A32CC, and the authored
   #C8C8C8 appears nowhere.

   ── WHAT IT READS, AND WHY THE NAIVE PREDICATE IS RIGHT *HERE* ──────────
   `(contains? node :opa)`, and nothing else. That looks like the naive
   `opa != 255` test the opa RAILS make wrong, and it is not, because the
   rails have already been applied on the C side: `obj_effective_opa`
   (renderer/src/main.c) folds BOTH LV_STYLE_OPA and LV_STYLE_OPA_LAYERED
   from the node up to the root, returns TRANSP the instant a link is
   <= LV_OPA_MIN, SKIPS a link >= LV_OPA_MAX from the multiply, snaps the
   result to TRANSP/COVER at the same rails, and `dump_obj` emits the key
   ONLY when the result differs from LV_OPA_COVER. So on the DUMP side the
   key's mere presence is exactly 'a fade reaches this node's draws'.

   Read that as a warning about the other direction: a rule reading a STYLE
   value, or reimplementing the accumulation, owes the rails itself. This one
   does not, and stating which side it is on is the whole of the difference.

   ── WHAT IT DOES *NOT* CARVE OUT, deliberately ─────────────────────────
   - `opa` 0 (fully transparent). It is still whole-widget opacity over
     glyphs, and the resulting defect — invisible text — is worse, not
     benign. A rail carve-out here would be a hole in the one predicate.
   - A HIDDEN node, or one under a hidden ancestor. `lv_obj_refr` returns
     immediately on LV_OBJ_FLAG_HIDDEN so nothing is drawn TODAY, but the ban
     is on the AUTHORING: a faded text subtree that is currently hidden
     becomes a live hazard the moment it is shown, and no dump of the hidden
     state can prove the shown state safe.
   - A DISABLED node. `LV_STATE_DISABLED` is where this hazard mostly lives
     (that is what the theme's four disabled styles are for); exempting it
     would exempt the whole population the rule exists for.

   ── WHAT IT MUST NOT CONDEMN ───────────────────────────────────────────
   THE PRECONDITION IS GLYPHS, NOT A CLASS LIST — and the theme uses the fade
   DELIBERATELY for text-free geometry, where the critical content is a
   SHAPE, there is no glyph self-contrast to collapse, and opacity alone is
   the right signal (`disabled_dim` on slider/switch/arc/bar/led and the
   checkbox INDICATOR part; `disabled_flat` on the table's line-art grid). A
   clause that reds the arc is a false gate that happens to be the right
   colour, so `text-free-classes` below is judged with the same weight as the
   condemning set, and `opa-test` proves BOTH halves.

   Note the trap the theme's own comment fell into and this file must not
   repeat: its reasoning bullet argued correctly from glyph self-contrast,
   and its listing bullet then named four glyph-bearing classes under
   'critical content is geometry, not glyphs'. Check call sites against the
   doctrine, not just the doctrine.

   ── WHAT IT CANNOT SEE, so a green is not over-read ────────────────────
   - THE RECOLOR ARM, which is half of the mechanism above. `dump_obj`
     reports the colour LVGL actually draws (`style_color_drawn` applies the
     accumulated recolor before emitting `text_color` / `bg_color`), so the
     recolor's EFFECT is baked into the reported value and the recolor itself
     is invisible. UI-QUALITY-CONTRACTS §6.7 says what closes it, and it is
     the static tier rather than this lane.
   - A SYMBOL `bg_image_src`, which puts glyphs on ANY object including a
     plain container (`lv_obj_draw.c` resolves LV_IMAGE_SRC_SYMBOL into
     `bg_image_symbol_font`). It is a STYLE, not a class, so no class set can
     ever grow to cover it; it is reachable from the ui_ast
     (PROP_BG_IMAGE_SRC) and the dump carries no `bg_image_src`. NO CORPUS
     CARD SETS ONE — measured, which is why this rule assumes it away rather
     than guessing — and a consumer's screens are not bound by that.
   - A STATE OR THEME NO CARD RENDERS. This lane judges rendered dumps; the
     authored space is larger than any corpus.

   ── WHY IT DOES NOT READ THE CLASSIFICATION TABLE ──────────────────────
   `devcards.classify`'s `:role :text` looks like the axis this clause wants
   and is a DIFFERENT quantity: its own table says it means 'a human reads
   this for a VALUE, glyphs or not', and it therefore covers lv_bar, lv_led,
   lv_chart, lv_scale and lv_table. A clause keyed on it would condemn
   exactly the text-free geometry §6 protects — the false gate in its most
   plausible form. The sets below are this clause's own, derived from the
   LVGL draw path rather than from intent.")

(set! *warn-on-reflection* true)

(def glyph-classes
  "Classes that put glyphs on screen THEMSELVES, so a fade on one is a
   violation with no further evidence needed.

   ENUMERATED FROM THE `lv_obj_init_draw_label_dsc` CALL SITES under
   lvgl/src/widgets, never guessed. The call sites are buttonmatrix,
   checkbox, dropdown, image, label, roller, scale, table and textarea; each
   row below says which of those it is and why it landed here rather than in
   `text-free-classes`.

   THAT DERIVATION IS SOUND FOR THE EMITTED SET AND IS *NOT* A GENERAL TEST,
   which is worth saying loudly because it reads like one. Measured against
   ten widgets this interpreter does not emit: `lv_arclabel` draws entirely
   through `lv_draw_letter` and `lv_span` builds its own
   `lv_draw_label_dsc_t`, so BOTH are glyph-bearing and BOTH are invisible to
   a search for `lv_obj_init_draw_label_dsc`; and `lv_keyboard` draws through
   its `lv_buttonmatrix` base, so a class-name test sees nothing there either.
   A consumer adding a widget owes the three-route question in
   UI-QUALITY-CONTRACTS §6.3, not a repeat of this grep. It happens to be
   exact HERE because every emitted class was checked against all three.

   - `lv_buttonmatrix` — button captions (LV_PART_ITEMS). The dump carries
     no cell text: LV_PART_ITEMS is one style read for a row of
     independently-stated buttons and there is no node to hang them on.
   - `lv_checkbox` — its own label, drawn on MAIN.
   - `lv_dropdown` — the selected option's text, drawn on MAIN.
   - `lv_dropdown-list` — the open popup's option list. The HYPHEN is
     LVGL's own spelling (`lv_dropdownlist_class.name`), where the roller's
     label uses an underscore; guessing that pair is exactly what this set
     spares a consumer.
   - `lv_roller` — LV_PART_SELECTED redraws the banded row's text over its
     own fill.
   - `lv_roller_label` — the unbanded option rows. It derives from
     `lv_label_class` and inherits the label draw, but `dump_obj` gates its
     `text` key on `lv_obj_check_type`, which compares the class pointer for
     EQUALITY — so a roller label's glyphs are real and the dump is silent
     about them. That is precisely why this is a class row rather than a
     `:text` read.
   - `lv_scale` — LV_PART_INDICATOR tick labels.
   - `lv_textarea` — the PLACEHOLDER, which lv_textarea draws ITSELF
     (lv_textarea.c, `txt[0] == '\\0' && ta->placeholder_txt`) rather than
     through its internal label child. A textarea showing only a
     placeholder therefore has real glyphs and an EMPTY child label, which
     is the case a `:text`-only rule silently passes.
   - `lv_spinbox` — an `lv_textarea` subclass, so it inherits that same
     placeholder draw.

   Not here, and each for a stated reason rather than by omission:
   `lv_button` and `lv_tabview` draw no text of their own (their captions
   are a child label and a child buttonmatrix, both judged on their own
   nodes), and `lv_image` is AMBIGUOUS from the dump — see
   `ambiguous-classes`."
  #{"lv_buttonmatrix" "lv_checkbox" "lv_dropdown" "lv_dropdown-list"
    "lv_roller" "lv_roller_label" "lv_scale" "lv_spinbox" "lv_textarea"})

(def text-free-classes
  "Classes that never put a glyph on screen, so the fade is legitimate on
   them. This set is load-bearing in the OPPOSITE direction from
   `glyph-classes`: every entry is something the theme deliberately fades,
   and a clause that condemned one would be a false gate.

   None of these EXCEPT `lv_table` appears among the
   `lv_obj_init_draw_label_dsc` callers, and none reaches `lv_draw_letter`, a
   self-built label dsc, or a base class that does — the three-route check of
   UI-QUALITY-CONTRACTS §6.3, run against each. That is the derivation, not an
   assertion that they look text-free. (A SYMBOL `bg_image_src` would put
   glyphs on any of them and is invisible here; see the ns docstring.)

   `lv_table` IS a caller and is here anyway, on a CONDITION that is written
   down in four places and holds today: `renderer.c`'s `table_props` decode
   arm sets row and column counts ONLY and never calls
   `lv_table_set_cell_value`, so every cell renders EMPTY and the subtree
   the ban protects is a grid of lines. WIRING CELL TEXT VOIDS THIS ROW:
   `lv_table` must move to `glyph-classes` in the same change that wires it,
   and `theme.c`'s table arm must move from `disabled_flat` to the
   token-pair swap. `opa-test/table-carve-out-still-holds` is the executable
   half of that retires-when — it reads `renderer.c` and fails the moment
   the call appears."
  #{"lv_arc" "lv_bar" "lv_button" "lv_chart" "lv_led" "lv_line" "lv_obj"
    "lv_slider" "lv_spinner" "lv_switch" "lv_table" "lv_tabview"})

(def ambiguous-classes
  "Classes whose glyph-bearing-ness the DUMP CANNOT DECIDE. A fade on one is
   the THIRD ANSWER — reported as `:cantTell`, never as clean and never as a
   violation, because either verdict would claim more than the measurement
   can see.

   `lv_image` draws a label dsc when and only when its src is a SYMBOL
   (`lv_image.c`, `src_type == LV_IMAGE_SRC_SYMBOL`). The renderer passes an
   arbitrary wire string straight to `lv_image_set_src`, so a symbol image is
   reachable from the ui_ast, and `dump_obj` carries no `src` — a faded image
   is a faded glyph run or a faded picture and nothing in the tree says
   which.

   A CONSUMER WHO KNOWS MAY OVERRIDE, and that is the intended escape rather
   than a loophole: declaring `lv_image` in `:opa/text-free-classes` (or in
   `:opa/glyph-classes`) resolves it, on the record, in the consumer's own
   config. `validate-sets!` deliberately does not forbid that — it forbids
   only the shipped sets contradicting each other."
  #{"lv_image"})

(def ^:private label-class
  "The one class the DUMP itself decides, so it is in no set above.
   `dump_obj` emits `text` for exactly `lv_label` (`lv_obj_check_type`, a
   class-pointer equality test), so a label's glyph-bearing-ness is read off
   the node: non-blank text = glyphs, empty text = none. `obj_draws_text`
   makes the same call on the C side and excludes an empty label on the same
   ground — reporting a finding for zero glyphs would be a finding with no
   subject."
  "lv_label")

(defn- class-set
  "The shipped set `k` with the consumer's additions unioned on. ADDITIVE by
   construction: a consumer may declare its own widgets, and may not delete a
   row of ours, because a deletion is how this gate would get quietly relaxed
   for the classes it was written for."
  [shipped thresholds k]
  (into shipped (get thresholds k #{})))

(defn- glyph-verdict
  "Does this node put glyphs on screen? `:yes`, `:no`, or `:unknown` — three
   answers, because two would make 'clean' and 'I could not look' the same
   empty vector."
  [node glyphs text-free]
  (let [t (:type node)]
    (cond
      (= label-class t) (if (seq (str (:text node))) :yes :no)
      (contains? glyphs t) :yes
      (contains? text-free t) :no
      :else :unknown)))

(defn- node-label
  [node]
  (str (:type node) (when-let [uid (:uid node)] (str "#" uid))))

(defn findings
  "Opacity-ban findings for one card. Reads ctx `:card-id`, `:nodes` (the
   registry's shared annotated walk) and `:thresholds`.

   One predicate selects the population — `(contains? node :opa)`, which the
   ns docstring shows is exactly 'a fade reaches this node's draws' — and the
   three-way `glyph-verdict` decides what each one is."
  [{:keys [card-id nodes thresholds]}]
  (let [glyphs (class-set glyph-classes thresholds :glyph-classes)
        text-free (class-set text-free-classes thresholds :text-free-classes)]
    (when-let [both (seq (sort (filter glyphs text-free)))]
      (throw (ex-info (str "class(es) " (vec both) " declared BOTH "
                           "glyph-bearing and text-free — the clause would "
                           "condemn and clear the same widget, and whichever "
                           "arm won would be an accident of ordering")
                      {:card card-id :classes (vec both)})))
    (into []
          (for [{:keys [node]} nodes
                :when (contains? node :opa)
                :let [verdict (glyph-verdict node glyphs text-free)]
                :when (not= :no verdict)
                :let [ambiguous? (contains? ambiguous-classes (:type node))]]
            (if (= :yes verdict)
              {:card card-id
               :invariant :opa-over-text
               :node (node-label node)
               :detail (str "whole-widget opacity " (:opa node)
                            " reaches a subtree that puts GLYPHS on screen — "
                            "LVGL has no per-run text exemption, so the glyph "
                            "and its fill are both re-composited and move "
                            "toward each other. Express DISABLED as a "
                            "token-pair swap (UI-QUALITY-CONTRACTS §6)")}
              {:card card-id
               :invariant (if ambiguous? :opa-over-possible-text
                              :opa-unclassified-class)
               :node (node-label node)
               :act/outcome :cantTell
               :act/reason (if ambiguous? :glyph-source-unserialised
                               :class-not-declared)
               :detail (str "whole-widget opacity " (:opa node)
                            " reaches a node whose glyph-bearing-ness this "
                            "dump cannot decide — declare the class in "
                            "devcards.opa's sets (or the :opa/glyph-classes "
                            "/ :opa/text-free-classes thresholds) rather "
                            "than leaving the fade unjudged")})))))

(def producer
  "The registry entry, ARMED by `devcards.lanes` on both the atomic and the
   composition lane.

   MEASURED BEFORE ARMING, over all 244 cards in BOTH themes (488 renders,
   2590 nodes): exactly 38 nodes carry `:opa`, every one of them at 127 and
   every one of them `disabled` — 10 lv_slider, 12 lv_switch, 6 lv_arc,
   6 lv_table, 2 lv_bar, 2 lv_led. NOT ONE carries glyphs, so this lane
   reports ZERO and arming it costs no exemption. Reproduce with
   `clojure -M:bindings:opa-text-probe` rather than trusting the count.

   The two absences in that census are the result, not the noise. No
   lv_checkbox appears, because the theme puts `disabled_dim` on the checkbox
   INDICATOR part and `obj_effective_opa` reads LV_PART_MAIN — so the
   part-scoped fade never reaches the node's own glyphs, which is the design.
   And no lv_button, lv_dropdown, lv_roller, lv_textarea or lv_tabview
   appears, because those are exactly the classes the token-pair swap took
   off the fade.

   DECLARES `:cantTell`, which makes it the first producer in this repo that
   can. Two consequences worth knowing rather than discovering: the shipped
   policy BLOCKS on `:cantTell` (`outcome/default-fail-outcomes`), so a third
   answer is a red gate and not a footnote; and `outcome/verdict`'s
   NOT-EXERCISED line becomes a real measurement for that outcome instead of
   a permanently-out-of-scope one.

   AND THAT DECLARATION IS WHY THIS IS ITS OWN PRODUCER, which was the real
   design decision here and is recorded so the next author does not re-open
   it. The cheaper shape was a clause inside `invariants/tree-findings`: that
   lane is already armed on every card, so nothing in `devcards.lanes` would
   have had to change and two sibling branches editing that file would not
   have had to be sequenced against this one. It was rejected on the merits.
   `tree-findings` is reached through producers that declare no `:outcomes`,
   and `findings/validate-producers!` therefore holds them to
   `outcome/legacy-outcomes` — `:failed` alone. A clause living there CANNOT
   emit `:cantTell`; it would throw at `check-outcome!`. Its only options for
   a node whose glyph-bearing-ness the dump cannot decide would be to call it
   a violation (claiming more than the measurement can see) or to clear it
   (the silent skip this standard refuses everywhere). Neither is available
   to a rule in this document's own terms, so the third answer is what buys
   the separate producer."
  {:id :opa
   :fn findings
   :requires #{:nodes}
   :outcomes #{:failed :cantTell}
   :reasons
   {:glyph-source-unserialised
    (str "the class can draw glyphs, but whether THIS instance does depends "
         "on a value dump_tree does not carry (an lv_image's src)")
    :class-not-declared
    (str "the class appears in none of devcards.opa's sets, so whether it "
         "puts glyphs on screen has never been decided")}
   :thresholds
   {:glyph-classes
    {:pred #(and (set? %) (every? string? %))
     :default #{}
     :doc (str "consumer widget classes that draw their own glyphs; UNIONED "
               "onto the shipped set, never replacing it")}
    :text-free-classes
    {:pred #(and (set? %) (every? string? %))
     :default #{}
     :doc (str "consumer widget classes that never put a glyph on screen; "
               "UNIONED onto the shipped set, never replacing it")}}})

(def emitted-class-coverage
  "The classes this clause has an answer for, as one set — the anti-rot
   anchor `opa-test` compares against `lvgl-classes/emitted-classes`.

   Kept here rather than in the test so the totality claim lives beside the
   sets it is about. A class this union misses is not silently cleared: it
   reaches `:opa-unclassified-class`, which BLOCKS. The test exists so that
   happens at `devcards-test` time rather than on the first card that fades
   the new widget."
  (into #{label-class} (concat glyph-classes text-free-classes
                               ambiguous-classes)))

(defn validate-sets!
  "Refuse overlapping declarations at LOAD time as well as at judgment time.
   `findings` throws on a consumer-supplied collision; this catches the
   shipped sets colliding with each other, which no consumer can fix."
  []
  (doseq [[a b] [[glyph-classes text-free-classes]
                 [glyph-classes ambiguous-classes]
                 [text-free-classes ambiguous-classes]]]
    (when-let [both (seq (sort (filter a b)))]
      (throw (ex-info (str "shipped class sets overlap on " (vec both))
                      {:classes (vec both)}))))
  (when (or (contains? glyph-classes label-class)
            (contains? text-free-classes label-class)
            (contains? ambiguous-classes label-class))
    (throw (ex-info (str label-class " is decided from the node's own `text` "
                         "key and must appear in no class set")
                    {})))
  true)

(validate-sets!)
