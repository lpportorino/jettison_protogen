(ns devcards.overlap
  "The OVERLAP rule — no two independently-placed interactive elements may
   share a pixel (or sit closer than the configured gap).

   The hazard is input, not looks: when two things that can take the
   pointer occupy the same pixel, exactly one of them gets the press and
   the other is dead there. `lv_indev_search_obj` walks children in
   REVERSE order and returns the FIRST hit (lv_indev.c), so the later
   sibling silently wins and the earlier one has a hole in it that no
   pixel oracle can see — the framebuffer is identical whether the
   occluded control was reachable or not.

   WHAT PARTICIPATES — the classification table's :interactive? axis,
   which is a statement about the TYPE. Decoration overlapping decoration
   is composition; a label over a panel is composition; two controls in
   the same place is the defect.

   WHAT IS EXCLUDED, and the citation for each:

   - RELATED nodes (one is an ancestor of the other). Containment is how
     composition works — a button's label is inside the button by
     construction. This is not a nicety: without it the rule reports every
     button against its own label, and a run that goes red for nesting
     rather than for the hazard is a false gate that happens to be the
     right colour.

   - HIDDEN nodes and their whole subtree. `lv_indev_search_obj` returns
     NULL immediately for LV_OBJ_FLAG_HIDDEN (lv_indev.c:623, 'if this obj
     is hidden the children are hidden too'), so a hidden node cannot take
     the pointer and cannot deny it to anyone else.

   WHAT IS **NOT** EXCLUDED, against the intuition:

   - A DISABLED node. LV_STATE_DISABLED does NOT keep a widget out of the
     pointer path. `lv_obj_hit_test` gates on LV_OBJ_FLAG_CLICKABLE alone
     (lv_obj_pos.c:1201) and `lv_indev_search_obj` skips only HIDDEN, so a
     disabled widget is still FOUND and still claimed as indev_obj_act;
     DISABLED is consulted much later, gating only whether the press EVENT
     is sent (lv_indev.c:1339/1391/1403). This renderer's `enabled_when`
     binding toggles the state and nothing else — it never clears
     CLICKABLE (renderer/src/renderer.c:1722-1750). So a disabled control
     painted over an enabled one absorbs the press and drops it: the worst
     version of this defect, not the benign one, because the control
     underneath is dead AND nothing anywhere reports a press. Exempting
     disabled overlaps would bless exactly that.

   KNOWN INACCURACY, stated so neither a clean lane nor a firing one is
   over-read.

   OVER-reports, and this one is DESIGNED: a designed affordance stack
   fires as a true collision. The renderer builds a resizable host_proxy as
   a full-bleed glass plus four corner handles (renderer.c:2255-2288), all
   CLICKABLE siblings, all shown together — genuinely competing for the
   pointer, and genuinely intended. Ordering that by declaration is the
   layer contract's job, not this rule's.

   REACHABILITY FACTS THE DUMP CANNOT EXPRESS. None occurs in this corpus,
   which is why the rule assumes them away rather than guessing; each is
   stated because a consumer's screens are not bound by that. Read this as
   the rule's known blind spots, not as a closed set — the test for adding
   one is whether `lv_indev_search_obj` consults something the dump omits.

   - A TRANSFORM. `lv_indev_search_obj` inverse-transforms the point
     (lv_indev.c:626) BEFORE both the descent gate and the hit test, while
     the dump's `:coords` are untransformed. Under a non-identity scale or
     rotation the reachable box computed here is therefore wrong in BOTH
     directions. This is reachable from the ui_ast vocabulary —
     PROP_SCALE_X/Y, PROP_ROTATION, PROP_PIVOT_X/Y (renderer.c:3407-3435) —
     so a consumer that transforms an interactive subtree gets answers this
     rule cannot justify. No corpus card sets any of them.
   - LV_OBJ_FLAG_OVERFLOW_VISIBLE widens the descent gate below by
     `lv_obj_get_ext_draw_size` (lv_indev.c:632-635). Nothing sets that
     flag anywhere — not renderer/src, not LVGL itself — so on THIS tree
     the gate is exactly `:coords`. Were a consumer to set it, this rule
     would clip too hard and UNDER-report.
   - LV_OBJ_FLAG_ADV_HITTEST lets a widget refuse a hit inside its own box
     by answering LV_EVENT_HIT_TEST (lv_obj_pos.c). Only lv_image sets it
     (lv_image.c:694) and lv_image clears CLICKABLE at construction, so no
     node reaches the pairing with it set. A consumer that re-adds
     CLICKABLE to an image would get an OVER-report, because the answer
     lives in an event handler no dump can serialise."
  (:require [devcards.classify :as classify]
            [devcards.geometry :as geometry]
            [devcards.invariants :as invariants]))

(set! *warn-on-reflection* true)

(defn pointer-reachable?
  "Can this annotated entry take the pointer at all? Two structural denials,
   and DISABLED is deliberately neither of them (see the ns docstring):

   - HIDDEN, or under a hidden ancestor: `lv_indev_search_obj` returns NULL
     for the node and its whole subtree.
   - CLICKABLE cleared: `lv_obj_hit_test` gates on that flag alone, so the
     pointer falls straight through. This is a per-INSTANCE fact — the
     renderer clears the flag at runtime on a STATIC host_proxy so the
     surface is see-through to input — which a type-keyed classification
     table cannot express. dump_obj emits `\"clickable\":false` only when the
     flag is clear, so an ABSENT key means clickable."
  [{:keys [node hidden-under?]}]
  (and (not (:hidden node))
       (not hidden-under?)
       (not (false? (:clickable node)))))

(defn- hit-box
  "The box this node is HIT-TESTED against, before its ancestors get a say.
   `lv_obj_hit_test` uses `lv_obj_get_click_area` — coords GROWN by
   ext_click_pad — never coords, so a widget that extends its touch target
   is tested against a larger box than it draws. dump_obj emits
   `click_area` only when the two differ, so falling back to `:coords` is
   exact rather than approximate.

   This is only HALF the reachability question: passing the hit test does
   not mean the pointer ever arrives. See `reachability`."
  [node]
  (or (:click_area node) (:coords node)))

(defn- descend-gate
  "The box an ancestor must contain the point within before
   `lv_indev_search_obj` will look at its children at all
   (lv_indev.c:631-646). It is the ancestor's `:coords` — NOT its click
   area, which the descent gate never consults, and not its ext draw size,
   which only applies under OVERFLOW_VISIBLE (see the ns docstring)."
  [node]
  (:coords node))

(defn- ancestor-nodes
  "The node maps of every STRICT ancestor of `path`, outermost first.

   `annotate-tree` carries `:path` (the vector of child indices) and the
   immediate `:parent`, but not the chain — and the descent gate is a
   property of the WHOLE chain: a click area can survive its parent's box
   and still be cut off by its grandparent's. Because `:path` is a vector
   of indices, an ancestor is exactly a prefix, so one index over the
   shared walk answers it without re-walking the tree."
  [path->node path]
  (map #(get path->node (subvec path 0 %)) (range (count path))))

(defn reachability
  "Where this node can ACTUALLY take the pointer, as [status box]:

     [:box b]           b is the reachable region
     [:unreachable nil] no pixel of it is reachable — a determination, not
                        a gap in knowledge, so it is not a finding
     [:unmeasurable why] some box in the chain is missing, so the answer is
                        unknown and the caller owes a finding

   `lv_indev_search_obj` descends into a node's children only while the
   point stays inside each ancestor's `:coords`, then hit-tests the node
   against its own click area. So reachability is the click area
   INTERSECTED with every ancestor's descent gate — a click area that
   leaks outside its parent is dead pixels, and reporting them names a
   hazard region no pointer can visit."
  [path->node {:keys [node path]}]
  (let [own (hit-box node)]
    (if (nil? own)
      [:unmeasurable "it has no :coords"]
      (loop [box own
             ancs (seq (ancestor-nodes path->node path))]
        (if-not ancs
          [:box box]
          (let [anc (first ancs)
                gate (descend-gate anc)]
            (if (nil? gate)
              [:unmeasurable (str "its ancestor " (pr-str (:type anc))
                                  " has no :coords, so the descent gate "
                                  "there is unknown")]
              (if-let [clipped (geometry/intersection box gate)]
                (recur clipped (next ancs))
                [:unreachable nil]))))))))

(defn proxy-scope
  "The id of the proxy this entry sits inside, or nil.

   Two nodes in the SAME proxy where at least one is an affordance are the
   designed glass-over-content / glass-over-handle stack that
   UI-QUALITY-CONTRACTS §1.5b establishes: a non-static proxy IS the
   interaction target, its glass takes every press inside the box, and the
   affordances are the interpreter's own composition.

   The interpreter DECLARES this — `dump_obj` emits `proxy_root` on the box
   and `proxy_part`/`proxy_owner` on each affordance it builds — so the rule
   reads an intent rather than inferring one from paint order, which §1.2
   forbids. It has to come from the renderer because those objects never
   pass through finalize_widget and therefore carry no uid; nothing else can
   name them, in this repo or in any consumer."
  [path->node {:keys [node path]}]
  (or (:proxy_owner node)
      (:proxy_root node)
      (some (fn [i]
              (let [anc (get path->node (subvec path 0 i))]
                (or (:proxy_owner anc) (:proxy_root anc))))
            (range (count path) 0 -1))))

(defn designed-proxy-stack?
  "Is this pair the documented intra-proxy stack? BOTH nodes in the same
   proxy AND at least one an affordance the renderer built.

   Deliberately narrow. A pair spanning TWO proxies still fires — their
   relative stacking lives in the compositor and is unjudgeable from here
   (§1.6). An affordance against a node outside its proxy still fires. And
   two ordinary content children of one proxy still fire, because nothing
   about the proxy makes THEIR overlap intentional."
  [path->node a b]
  (let [sa (proxy-scope path->node a)
        sb (proxy-scope path->node b)]
    (and sa (= sa sb)
         (or (:proxy_part (:node a)) (:proxy_part (:node b))))))

(defn label-of
  [{:keys [node]}]
  (str (:type node) (when-let [uid (:uid node)] (str "#" uid))))

(defn- state-note
  "Names the disabled participants in a pair, because that combination is
   the silent one: no event is sent for a disabled top node, so the press
   vanishes rather than landing on the wrong widget."
  [a b]
  (let [dis (filterv #(get-in % [:node :disabled]) [a b])]
    (case (count dis)
      0 ""
      2 " (BOTH disabled — still in the pointer path; the press is absorbed and dropped)"
      (str " (" (label-of (first dis))
           " is DISABLED — still in the pointer path, so it absorbs the press"
           " and drops it)"))))

(defn findings
  "Overlap findings for one card. Reads ctx :card-id, :nodes (the shared
   annotated walk — ancestry included), :classes and :thresholds
   {:gap-px n}.

   Every node whose type the table does not classify yields an
   :unclassified-type finding and is left OUT of the pairing: its
   interactivity is unknown, so including it would invent a hazard and
   excluding it silently would hide one. The finding is the third option —
   the gate says out loud what it could not judge.

   Boxes are compared as REACHABLE regions, not as drawn or hit-test
   boxes: a click area is clipped by every ancestor's descent gate before
   any pair is measured, so both the verdict and the reported coordinates
   name pixels a pointer can actually visit."
  [{:keys [card-id nodes classes thresholds]}]
  (classify/validate-table! classes)
  (let [gap-px (:gap-px thresholds 0)
        unclassified (classify/unclassified-findings card-id classes nodes :overlap)
        interactive? (fn [entry]
                       (and (pointer-reachable? entry)
                            (:interactive?
                             (classify/classify classes (:type (:node entry))))))
        path->node (into {} (map (juxt :path :node)) nodes)
        judged (into []
                     (comp (filter interactive?)
                           (map #(assoc % ::reach (reachability path->node %))))
                     nodes)
        ;; A node that WOULD be judged but cannot be measured is a finding,
        ;; never a quiet drop. Skipping it silently is the exact shape
        ;; geometry/check-box! exists to prevent — an unmeasurable node
        ;; reading as "far apart" makes a broken run and a clean run
        ;; produce the same empty vector.
        ;;
        ;; :unreachable is deliberately NOT in here. That is a positive
        ;; determination that the pointer can never arrive — the same class
        ;; as a cleared CLICKABLE — not an admission of ignorance, and
        ;; reporting it would turn "correctly excluded" into noise.
        unmeasurable (for [e judged
                           :let [[status why] (::reach e)]
                           :when (= :unmeasurable status)]
                       {:card card-id
                        :invariant :unmeasurable-node
                        :node (label-of e)
                        :detail (str "interactive node could not be judged: "
                                     why " — it is NOT thereby clear of "
                                     "everything else")})
        reach-of (fn [e] (second (::reach e)))
        candidates (filterv #(= :box (first (::reach %))) judged)]
    (into (into unclassified unmeasurable)
          (for [[i a] (map-indexed vector candidates)
                b (subvec candidates (inc i))
                :when (not (invariants/related? a b))
                :when (not (designed-proxy-stack? path->node a b))
                :let [sep (geometry/separation (reach-of a) (reach-of b))]
                :when (< sep gap-px)]
            {:card card-id
             :invariant :overlap
             :node (str (label-of a) " vs " (label-of b))
             :detail (str "two interactive elements "
                          (if (neg? sep)
                            (str "SHARE pixels (overlap depth " (- sep) "px)")
                            (str "sit " sep "px apart, under the " gap-px
                                 "px minimum"))
                          " — " (geometry/describe (reach-of a))
                          " vs " (geometry/describe (reach-of b))
                          ". Exactly one can take the pointer there"
                          (state-note a b))}))))

(def producer
  "The registry entry. Not in `devcards.findings/builtin-producers` — that
   vector is `card-findings`' DEFAULT menu, not the armed set — but ARMED
   nonetheless: `devcards.lanes` passes it on both the atomic and the
   composition lane, with the shipped starter table and `:overlap/gap-px 0`.

   It reports ZERO on this corpus, and getting there is the part worth
   knowing, because none of it was silencing.

   THE INTERPRETER'S OWN STACKS resolved by DECLARATION. dump_obj marks the
   proxy box (`proxy_root`) and every affordance the renderer builds
   (`proxy_part` + `proxy_owner`); `designed-proxy-stack?` excludes a pair
   only when both sit in one proxy and at least one is an affordance. That
   is the §1.5b design — a non-static proxy IS the interaction target and
   its glass takes every press inside the box. The declaration has to come
   from the renderer: those objects never reach `finalize_widget`, so they
   are uid-free here and in every consumer, and the LAYER contract (uid-keyed)
   cannot name them at all.

   THE AUTHORED ONES resolved by CONSTRUCTION. The scrubber's buffered bar
   is an underlay beneath a transparent-track slider, and the gauge arc is a
   display; both were CLICKABLE, sitting in the pointer path competing for
   presses they must never win. Clearing the flag removes them from it —
   decoration in the pointer path is a latent hazard whether or not this
   rule reports it.

   RE-MEASURE rather than trusting any count in prose. The `class-census`
   probe walks every card and prints what fires; a number here goes stale
   the first time the corpus, the table or the renderer moves — which is
   exactly what happened to the census this docstring used to carry."

  {:id :overlap
   :fn findings
   :requires #{:nodes :classes}
   :thresholds {:gap-px {:pred nat-int?
                         :default 0
                         :doc (str "minimum clear pixels between two "
                                   "interactive elements; 0 = they may "
                                   "touch but not overlap, 1 = they may "
                                   "not touch")}}})
