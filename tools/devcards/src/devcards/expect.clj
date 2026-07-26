(ns devcards.expect
  "protogen's own corpus POLICY: how `corpus/spec.edn`'s `:expect`
   vocabulary routes the DOM lane.

   Separate from `devcards.core` — which owns the CLI, the render loop and
   the golden manifests — for one reason: core has to load the generated
   protobuf bindings, so nothing that requires it can be unit-tested
   without first compiling `target/proto-classes`. That is exactly why this
   producer shipped with no test. The routing is pure tree-in/findings-out
   and needs none of that, so it lives where it can be judged.

   Separate from `devcards.findings` for the opposite reason: `:expect` is
   this corpus' vocabulary, not a registry concern. A consumer's corpus
   declares what ITS cards are for, and says so through its own producer."
  (:require [devcards.invariants :as invariants]))

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
