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

   KNOWN INACCURACY IN BOTH DIRECTIONS, stated so neither a clean lane nor
   a firing one is over-read.

   UNDER-reports: the rule measures `:coords`, while the pointer is tested
   against `lv_obj_get_click_area` — coords GROWN by ext_click_area. Where
   a widget sets an extended click area the true hazard boundary is larger
   than the box judged here, so a clean overlap lane is NOT a claim that no
   two click areas touch. Closing that needs the dump to emit the click
   area.

   OVER-reports, two ways, both live in this renderer:
   - a STATIC host_proxy box has LV_OBJ_FLAG_CLICKABLE cleared at runtime
     (renderer.c:1966) so the pointer falls through it, but nothing in the
     dump says so — a type-keyed :interactive? still counts it in;
   - a DESIGNED affordance stack fires as a true collision. The renderer
     builds a resizable host_proxy as a full-bleed glass plus four corner
     handles (renderer.c:2255-2288), all CLICKABLE siblings, all shown
     together — genuinely competing for the pointer, and genuinely
     intended. Ordering that by declaration is the layer contract's job,
     not this rule's."
  (:require [devcards.classify :as classify]
            [devcards.geometry :as geometry]
            [devcards.invariants :as invariants]))

(set! *warn-on-reflection* true)

(defn- pointer-reachable?
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

(defn- hazard-box
  "The box the POINTER is actually tested against. `lv_obj_hit_test` uses
   `lv_obj_get_click_area` — coords GROWN by ext_click_pad — never coords,
   so a widget that extends its touch target has a hazard boundary larger
   than the box it draws. dump_obj emits `click_area` only when the two
   differ, so falling back to `:coords` is exact rather than approximate."
  [node]
  (or (:click_area node) (:coords node)))

(defn- label-of
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
   the gate says out loud what it could not judge."
  [{:keys [card-id nodes classes thresholds]}]
  (classify/validate-table! classes)
  (let [gap-px (:gap-px thresholds 0)
        unclassified (classify/unclassified-findings card-id classes nodes :overlap)
        interactive? (fn [entry]
                       (and (pointer-reachable? entry)
                            (:interactive?
                             (classify/classify classes (:type (:node entry))))))
        ;; A node that WOULD be judged but carries no usable :coords is a
        ;; finding, never a quiet drop. Skipping it silently is the exact
        ;; shape geometry/check-box! exists to prevent — an unmeasurable
        ;; node reading as "far apart" makes a broken run and a clean run
        ;; produce the same empty vector.
        no-coords (for [e nodes
                        :when (and (interactive? e) (nil? (hazard-box (:node e))))]
                    {:card card-id
                     :invariant :unmeasurable-node
                     :node (label-of e)
                     :detail (str "interactive node has no :coords, so overlap "
                                  "could not judge it — it is NOT thereby "
                                  "clear of everything else")})
        candidates (into [] (filter #(and (interactive? %) (hazard-box (:node %)))) nodes)]
    (into (into unclassified no-coords)
          (for [[i a] (map-indexed vector candidates)
                b (subvec candidates (inc i))
                :when (not (invariants/related? a b))
                :let [sep (geometry/separation (hazard-box (:node a))
                                               (hazard-box (:node b)))]
                :when (< sep gap-px)]
            {:card card-id
             :invariant :overlap
             :node (str (label-of a) " vs " (label-of b))
             :detail (str "two interactive elements "
                          (if (neg? sep)
                            (str "SHARE pixels (overlap depth " (- sep) "px)")
                            (str "sit " sep "px apart, under the " gap-px
                                 "px minimum"))
                          " — " (geometry/describe (hazard-box (:node a)))
                          " vs " (geometry/describe (hazard-box (:node b)))
                          ". Exactly one can take the pointer there"
                          (state-note a b))}))))

(def producer
  "The registry entry. NOT in `devcards.findings/builtin-producers`:
   arming it against protogen's own corpus is a separate change that owes
   its own evidence — and the evidence, measured by the `class-census`
   probe, is that it would fire. Under the starter table this corpus
   reports lv_bar vs lv_slider on the scrubber legos and lv_obj vs lv_obj
   on the host_proxy and hud-overlay cards. Those are TRUE pointer-path
   collisions on DESIGNED stacks — benign by paint order, or by a runtime
   CLICKABLE clear the type-keyed table cannot see. Arming this rule alone
   would therefore turn correct cards red and the only fix available today
   would be per-card exemptions, which is the ratchet the devcards rules
   exist to avoid feeding. It is the layer contract that resolves 'benign
   because underneath' by declaration.

   A consumer arms it by appending this map to its producer vector and
   supplying :classes."
  {:id :overlap
   :fn findings
   :requires #{:nodes :classes}
   :thresholds {:gap-px {:pred nat-int?
                         :default 0
                         :doc (str "minimum clear pixels between two "
                                   "interactive elements; 0 = they may "
                                   "touch but not overlap, 1 = they may "
                                   "not touch")}}})
