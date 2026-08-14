(ns devcards.spacing
  "The SPACING rule — no element's PAINT may eat the clearance the layout
   gave to another element.

   The hazard is looks, not input, which is what separates this from
   `devcards.overlap`. That rule asks \"can one element steal another's
   press?\" and two abutting elements cannot, so its `gap-px` is 0 and
   raising it is refused: it would report LAYOUT from a pointer gate. This
   rule asks the other question, and asks it about the pixels that are
   actually drawn.

   WHY A NODE-BOX METRIC CANNOT ANSWER IT. `coords` is what a widget
   DECLARED; it is not the extent of what it DRAWS. `lv_slider`'s knob is
   the standing case: `position_knob` centres the knob on the indicator end
   and then grows it by the LV_PART_KNOB padding (lv_slider.c), which the
   stock theme sets to `LV_DPX_CALC(dpi, 6)` — this corpus' own slider cards
   describe the result as \"6px pad_all => ~28px visual diameter on a 16px
   track\" and carry a bleed margin so the PNG oracle can see it. So a
   slider whose box clears its neighbour by 4px paints 2px INTO it, and
   every rule reading `coords` reports that card clean. `dump_obj` emits
   `paint_box` / `paint_bound` for exactly this, and this rule is their
   consumer.

   WHAT FIRES, and it is deliberately not \"these two are close\":

     the layout allocated at least `gap-px` clear pixels between two
     elements, AND their painted extents leave fewer than that.

   The second half alone would report ABUTMENT, which is how layout works
   here — a tab bar meets its content area, one tab button meets the next,
   a scrubber's rows stack flush. `lanes/overlap-thresholds` records the
   measurement: at a 1px threshold over node boxes the corpus yields 80
   findings, 66 of them tabview abutments, and calls raising it \"flooding
   the lane with LAYOUT, not with hazards\". The first half is what keeps
   that flood out WITHOUT an exclusion list: two elements the author placed
   flush are placed flush on purpose, and whether that is too tight is a
   design question `:vlm/alignment-defect` owns. What this rule owns is the
   pair the author placed APART and the renderer brought together — a
   defect with no author, which is why nothing else was going to catch it.

   BOTH BOXES ARE CLIPPED BY EVERY ANCESTOR FIRST, and skipping that would
   invent findings rather than miss them. `refr_obj` intersects the layer's
   clip area with each ancestor's box before drawing its children — with
   `coords`, or with `coords` grown by the resolved ext draw size when that
   ancestor carries LV_OBJ_FLAG_OVERFLOW_VISIBLE (lv_refr.c). So an overhang
   that escapes its container is never painted at all, and a knob hanging
   off the bottom of a flush-fitted panel encroaches on nothing. This is the
   same shape `overlap/reachability` applies to the pointer path, one
   pipeline over.

   IT READS `descend_gate` TO GET THAT BOX, and the sharing is worth stating
   because the two readers want it for unrelated reasons: the pointer lane
   reads it as `lv_indev_search_obj`'s child-descent gate, this one as
   `refr_obj`'s child clip. LVGL computes them identically — same flag, same
   ext draw size — so one emitted key answers both. That is LVGL's
   coincidence and not an assumption of ours; should the two ever diverge,
   this rule needs its own key and the absence convention below is what
   would have to change with it.

   WHAT IS EXCLUDED, and the citation for each:

   - RELATED nodes (one an ancestor of the other). Containment is how
     composition works; without it the rule reports every button against
     its own label. Same argument, same helper, as the overlap rule.

   - HIDDEN nodes and their whole subtree, and nodes the tabview carousel
     has SNAPPED out of view. A node that is not drawn cannot crowd
     anything. `annotate-tree` carries both facts.

   - A node whose ancestors clip it to NOTHING. Its extent is empty, so it
     paints no pixels, so it has no neighbours. This is a determination
     rather than a gap in knowledge and earns no finding — the same
     distinction `overlap/reachability` draws between `:unreachable` and
     `:unmeasurable`.

   - Pairs the LAYOUT already placed within `gap-px`, per the argument
     above. Note what this is NOT: it is not an exclusion of any node, and
     no declaration can claim it. A pair is out of scope only while the
     author's own boxes are the thing that is tight.

   WHAT IS **NOT** EXCLUDED, against the intuition:

   - A DECLARED overlay, and a proxy's affordance stack. Those exist to let
     the OVERLAP rule accept an intended pointer collision, and they buy
     nothing here because they cannot arise: an overlay covers what it
     declares over, so those node boxes intersect, so the pair is already
     out of scope by the layout half of the test. No `designed_overlay` or
     `proxy_part` reader belongs in this rule, and adding one would be an
     exclusion with no case behind it.

   - A DECORATIVE node. This rule judges every drawn element regardless of
     the classification table's `:interactive?` axis, because crowding is
     about pixels and a label crowded by a knob looks exactly as wrong as a
     button would. That is why this producer requires no `:classes` and
     emits no `:unclassified-type` finding: it asks nothing of a node's
     type, so an undeclared type costs it no knowledge.

   THE THIRD ANSWER, which is the one this rule most needs. A widget whose
   exact paint extent could not be resolved carries `paint_bound`: a
   conservative box the paint provably stays inside, and nothing finer. That
   is sound for proving a NEGATIVE — a bound clear of its neighbour proves
   no encroachment, and those pairs are clean here with no caveat — and
   unsound for asserting one. So a pair that is clear under the bound is
   silent, and a pair that is NOT is reported as
   `:unmeasurable-paint-extent` rather than as crowding. A rule that cannot
   tell \"no overhang\" from \"overhang unknown\" reports a clean run over an
   unmeasured one, and this rule's clean value and its nothing-ran value are
   the same empty vector.

   KNOWN BLIND SPOTS, stated so neither a clean lane nor a firing one is
   over-read. Each is a fact `refr_obj` consults that the dump does not
   carry; none occurs in this corpus, which is why the rule assumes them
   away rather than guessing.

   - A TRANSFORM. Both boxes here live in the pre-transform space, exactly
     as `overlap`'s do, while a rotated or scaled subtree paints elsewhere.
     Reachable from the ui_ast vocabulary (PROP_SCALE_X/Y, PROP_ROTATION),
     set by no corpus card.
   - CLIP_CORNER. `refr_obj` additionally rounds a child clip to the
     parent's radius. This rule uses the rectangular clip, so along a rounded
     corner it credits a few pixels the parent actually cuts — which can only
     make it report a pair the corner would have separated, never hide one.
   - OPACITY. A fully transparent widget occupies its box here. Crowding by
     an invisible neighbour is not crowding, but `opa` is a resolved style
     the dump does carry, so this is a limit of the RULE rather than of the
     instrument, and `devcards.opa` is where that vocabulary already lives."
  (:require [devcards.geometry :as geometry]
            [devcards.invariants :as invariants]))

(set! *warn-on-reflection* true)

(defn child-clip
  "The box this node clips its CHILDREN to before they are drawn.

   `refr_obj` intersects the layer's clip area with the node's `coords`,
   or with `coords` grown by the resolved ext draw size when the node
   carries LV_OBJ_FLAG_OVERFLOW_VISIBLE (lv_refr.c). `dump_obj` emits
   `descend_gate` precisely on that second case and only when the two
   boxes differ, so falling back to `:coords` is EXACT and never means the
   producer forgot to look."
  [node]
  (or (:descend_gate node) (:coords node)))

(defn paint-extent
  "This node's painted extent, as [status box]:

     [:exact b]     b is where it paints, exactly
     [:bound b]     it paints somewhere inside b and nothing finer is known
     [:none nil]    it has no :coords, so nothing here can be measured

   `dump_obj` emits at most one of `paint_box` / `paint_bound`, and only
   when that box differs from `coords`. Absence of both is therefore a
   POSITIVE statement — `lv_obj_get_ext_draw_size` is 0, so `refr_obj`
   clips the widget's own drawing to `coords` and not one pixel escapes —
   never a producer that did not look."
  [node]
  (cond
    (:paint_box node) [:exact (:paint_box node)]
    (:paint_bound node) [:bound (:paint_bound node)]
    (:coords node) [:exact (:coords node)]
    :else [:none nil]))

(defn- ancestor-nodes
  "The node maps of every STRICT ancestor of `path`, outermost first.
   `:path` is a vector of child indices, so an ancestor is exactly a
   prefix and one index over the shared walk answers it."
  [path->node path]
  (map #(get path->node (subvec path 0 %)) (range (count path))))

(defn- clip-through-ancestors
  "`box` intersected with every strict ancestor's child clip, or nil when
   some ancestor cuts it away entirely. Returns :unmeasurable when an
   ancestor has no box to clip with — that is ignorance, not emptiness,
   and the two must not collapse into one another."
  [path->node path box]
  (loop [acc box
         ancs (seq (ancestor-nodes path->node path))]
    (cond
      (nil? ancs) [:box acc]
      :else (let [clip (child-clip (first ancs))]
              (cond
                (nil? clip) [:unmeasurable (str "its ancestor "
                                                (pr-str (:type (first ancs)))
                                                " has no :coords, so the clip "
                                                "there is unknown")]
                :else (if-let [cut (geometry/intersection acc clip)]
                        (recur cut (next ancs))
                        [:clipped-away nil]))))))

(defn drawn?
  "Is this entry drawn at all? Three structural denials, each a fact the
   dump states rather than one this rule infers: the node is HIDDEN, an
   ancestor is (so LVGL never reaches it), or the tabview carousel has
   snapped it out of view."
  [{:keys [node hidden-under? snapped-under?]}]
  (and (not (:hidden node))
       (not hidden-under?)
       (not snapped-under?)))

(defn label-of
  [{:keys [node]}]
  (str (:type node) (when-let [uid (:uid node)] (str "#" uid))))

(defn candidates
  "The entries this rule judges, each carrying its ancestor-clipped node
   box and painted extent.

   An entry whose boxes could not be resolved is kept, tagged
   ::unmeasurable with the reason, because `findings` owes a finding for
   it rather than a silent drop. An entry clipped away to nothing is
   DROPPED, because that is a determination that it paints no pixels."
  [nodes]
  (let [path->node (into {} (map (juxt :path :node)) nodes)]
    (into []
          (comp
           (filter drawn?)
           (keep (fn [{:keys [node path] :as entry}]
                   (let [[status paint] (paint-extent node)]
                     (if (= :none status)
                       (assoc entry ::unmeasurable "it has no :coords")
                       (let [[n-stat n-box] (clip-through-ancestors
                                             path->node path (:coords node))
                             [p-stat p-box] (clip-through-ancestors
                                             path->node path paint)]
                         (cond
                           (= :unmeasurable n-stat)
                           (assoc entry ::unmeasurable n-box)

                           (= :unmeasurable p-stat)
                           (assoc entry ::unmeasurable p-box)

                           ;; Clipped away on BOTH boxes: it paints nothing.
                           (and (= :clipped-away n-stat)
                                (= :clipped-away p-stat)) nil

                           ;; The paint survives where the node box does not —
                           ;; an overhang reaching past a clip its own coords
                           ;; never entered. Judge it on what is drawn.
                           (= :clipped-away n-stat)
                           (assoc entry
                                  ::node-box p-box
                                  ::paint p-box
                                  ::exact? (= :exact status))

                           (= :clipped-away p-stat) nil

                           :else (assoc entry
                                        ::node-box n-box
                                        ::paint p-box
                                        ::exact? (= :exact status)))))))))
          nodes)))

(defn node-box [entry] (::node-box entry))
(defn paint-outer [entry] (::paint entry))
(defn paint-exact? [entry] (::exact? entry))

(defn findings
  "Spacing findings for one card. Reads ctx :card-id, :nodes (the shared
   annotated walk) and :thresholds {:gap-px n}.

   Boxes are compared as DRAWN regions — the painted extent clipped by
   every ancestor — so both the verdict and the reported coordinates name
   pixels that are really on the screen."
  [{:keys [card-id nodes thresholds]}]
  (let [gap-px (:gap-px thresholds 3)
        cands (candidates nodes)
        judged (filterv #(nil? (::unmeasurable %)) cands)
        ;; A node that WOULD be judged but cannot be measured is a finding,
        ;; never a quiet drop: an unmeasurable node reading as "far from
        ;; everything" makes a broken run and a clean run produce the same
        ;; empty vector.
        unmeasurable (for [e cands
                           :let [why (::unmeasurable e)]
                           :when why]
                       {:card card-id
                        :invariant :unmeasurable-paint-extent
                        :node (label-of e)
                        :detail (str "drawn node could not be judged: " why
                                     " — it is NOT thereby clear of everything "
                                     "else")})]
    (into (vec unmeasurable)
          (for [[i a] (map-indexed vector judged)
                b (subvec judged (inc i))
                :when (not (invariants/related? a b))
                :let [node-sep (geometry/separation (node-box a) (node-box b))]
                ;; The LAYOUT half: only a pair the author placed at least
                ;; gap-px apart can have that clearance taken from it.
                :when (>= node-sep gap-px)
                :let [paint-sep (geometry/separation (paint-outer a)
                                                     (paint-outer b))]
                :when (< paint-sep gap-px)]
            (if (and (paint-exact? a) (paint-exact? b))
              {:card card-id
               :invariant :paint-crowding
               :node (str (label-of a) " vs " (label-of b))
               :detail (str "the layout left " node-sep "px between these two, "
                            "and what they DRAW leaves "
                            (if (neg? paint-sep)
                              (str "none — the painted boxes OVERLAP by "
                                   (- paint-sep) "px")
                              (str paint-sep "px"))
                            ", under the " gap-px "px minimum — "
                            (geometry/describe (paint-outer a)) " vs "
                            (geometry/describe (paint-outer b))
                            ". A rule reading :coords reports this pair clean")}
              {:card card-id
               :invariant :unmeasurable-paint-extent
               :node (str (label-of a) " vs " (label-of b))
               :detail (str "the layout left " node-sep "px between these two "
                            "and at least one paints an extent that could not "
                            "be resolved exactly; within the bound the "
                            "clearance may be as little as " paint-sep
                            "px, under the " gap-px "px minimum. Unknown, not "
                            "clean — " (geometry/describe (paint-outer a))
                            " vs " (geometry/describe (paint-outer b)))})))))

(def producer
  "The registry entry.

   TWO-WAY BY CONSTRUCTION, and that is the shape `docs/UI-QUALITY-
   CONTRACTS.md` §0 assigns: this is integer arithmetic on inclusive rects,
   which earns the `exact` row, so it declares no `:outcomes` and cannot
   later have one of its findings softened to `:cantTell` in a small diff —
   `findings/validate-producers!` throws on that.

   ONE THRESHOLD, `gap-px`, and it is NOT the overlap lane's. The two knobs
   answer different questions and share no value: overlap's is 0 because a
   pointer gate must not report layout, and this one is a clearance floor in
   drawn pixels. `findings/threshold-index` keys them by producer id, so
   both may be named `gap-px` without either shadowing the other — a
   property `findings-test`'s `two-producers-may-each-own-a-gap-px` pins.

   RE-MEASURE rather than trusting any count in prose: `dev/paint_census.clj`
   walks every card, prints the paint-extent population and every pair whose
   drawn separation differs from its declared one, and is what the default
   below was chosen against."
  {:id :spacing
   :fn findings
   :requires #{:nodes}
   :thresholds {:gap-px {:pred nat-int?
                         :default 3
                         :doc (str "minimum clear pixels between what two "
                                   "elements DRAW, applied only where their "
                                   "declared boxes already left that much. "
                                   "0 does NOT disable the rule — it is the "
                                   "strictest-negative setting, where only a "
                                   "painted OVERLAP between two "
                                   "non-overlapping boxes fires")}}})
