(ns devcards.lanes
  "The LANES protogen's own gate runs: which producers judge an atomic card,
   which judge a composition card, and how `corpus/spec.edn`'s `:expect`
   vocabulary routes the DOM lane.

   Separate from `devcards.core` — which owns the CLI, the render loop and
   the golden manifests — for one reason, and it is a testability one: core
   has to load the generated protobuf bindings, so NOTHING that requires it
   can be unit-tested without first compiling `target/proto-classes`, which
   the `:test` alias deliberately does not carry.

   That is not a detail. While the producer vectors were assembled inside
   `core.clj`, the only canaries that could be written pinned things that
   RESEMBLED the call sites — a registry helper, a producer in isolation —
   and a review found both of them green while the production expression
   they stood for had been reverted to its defective form. A lane that
   cannot be named in a test cannot be pinned by one. So the call sites live
   here, and `core.clj` calls them.

   Separate from `devcards.findings` for the opposite reason: `:expect` is
   THIS corpus' vocabulary, not a registry concern. A consumer's corpus
   declares what its own cards are for, through its own producer."
  (:require [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.lvgl-classes :as lvgl-classes]
            [devcards.overlap :as overlap]))

(set! *warn-on-reflection* true)

(def tree-producer
  "The DOM lane, routed by what the card is FOR.

   It is a producer rather than a bare function so protogen's own gate runs
   through the same registry every consumer is told to extend: a rule added
   to `devcards.findings` that the gate here could not express would make
   that instruction hollow.

   `:probe-defect` is the INVERTED arm and the reason this cannot be the
   plain tree lane: such a cell exists to EXHIBIT a defect flag, so its
   ABSENCE is the finding."
  {:id :tree-by-expect
   :requires #{:tree :caps :expect}
   :fn (fn [{:keys [card-id tree nodes caps expect]}]
         (case expect
           :probe-pixel-only []
           :probe-defect (if (some (fn [node]
                                     (some #(get node %) invariants/defect-flags))
                                   (tree-seq #(seq (:children %)) :children tree))
                           []
                           [{:card card-id
                             :invariant :probe-defect-absent
                             :detail (str "cell exists to EXHIBIT a defect flag; "
                                          "none present")}])
           ;; nil expect (kitchen sinks) and every judged expect → full lane
           (invariants/tree-findings card-id tree caps nodes)))})

(def overlap-classes
  "The classification table the overlap lane judges this corpus with: the
   shipped starter table, unextended. protogen is the table's author, so
   taking it as-is is the honest dogfood — a private override here would
   gate this repo on rules no consumer inherits."
  (lvgl-classes/merge-consumer {}))

(def overlap-thresholds
  "Strict overlap: a SHARED pixel fires, touching does not. Deliberately not
   1 — at gap-px 1 this corpus reports 97 findings, 66 of them lv_tabview
   pages touching by construction (UI-QUALITY-CONTRACTS §2.3). The lane is a
   pointer-hazard gate, and two elements that merely touch take no press
   from each other."
  {:overlap/gap-px 0})

(defn atomic-findings
  "The live findings for ONE atomic card — the exact call the gate makes.

   `expect` is the card's declared `:expect`, or nil for the kitchen sinks.
   It is defaulted to `:judged` HERE rather than passed through, because the
   registry treats a nil value as ABSENT and would refuse the call: a nil
   would not fall through to a lenient lane, it would throw at
   `check-requires!`. The kitchen sinks must be JUDGED, so the default has
   to be a real value."
  [id expect tree]
  (:live (findings/card-findings {:card-id (str id)
                                  :tree tree
                                  :caps {:vis-px? true}
                                  :expect (or expect :judged)
                                  :classes overlap-classes
                                  :thresholds overlap-thresholds
                                  :producers [tree-producer overlap/producer]})))

(defn composition-findings
  "The live findings for ONE composition card — the exact call the gate
   makes. A composition is rendered in both modes, so the emission lane is
   the by-mode producer rather than the `:emission` builtin.

   The DOM producer is selected BY NAME. `(first builtin-producers)` reads
   the same today and silently judges with a different rule the moment that
   vector is reordered or grown."
  [id tree emissions-by-mode]
  (:live (findings/card-findings {:card-id (str id)
                                  :tree tree
                                  :caps {:vis-px? true}
                                  :host-proxy? false
                                  :emissions-by-mode emissions-by-mode
                                  :classes overlap-classes
                                  :thresholds overlap-thresholds
                                  :producers [(findings/builtin-producer :tree)
                                              findings/emission-by-mode-producer
                                              overlap/producer]})))
