(ns devcards.spacing-test
  "Pure contract tests for the SPACING rule.

   EVERY EMPTY-RESULT ASSERTION HERE IS PAIRED WITH A CONTROL THAT MUST BE
   NON-EMPTY for the same input class. `(is (empty? fs))` also passes when
   the rule threw, never ran, or found no candidates at all, and this rule
   is the worst case for that: its clean value and its nothing-ran value are
   both `[]`, and its whole subject is a defect nothing else reports. So an
   exclusion is only demonstrated when the case it excludes is shown FIRING
   without the excluding property.

   The trees here are hand-written, which asserts a MODEL of the dump rather
   than the dump itself. The real-render half — the paint keys as the
   renderer actually emits them, over a card built through
   `fixtures/build-authored-card` — is `dev/spacing_canary.clj`, run by the
   `spacing-canary` battery target. Neither substitutes for the other: this
   file pins the reducer and its exclusions cheaply and exhaustively, the
   canary pins that the vocabulary it reduces is the one that exists."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]
            [devcards.overlap :as overlap]
            [devcards.spacing :as spacing]))

(defn- judge
  ([root] (judge root 3))
  ([root gap-px]
   (spacing/findings {:card-id "c"
                      :nodes (invariants/annotate-tree root)
                      :thresholds {:gap-px gap-px}})))

(defn- crowding [fs] (filterv #(= :paint-crowding (:invariant %)) fs))
(defn- unmeasurable [fs]
  (filterv #(= :unmeasurable-paint-extent (:invariant %)) fs))

(defn- root-with
  "A root whose own box is the whole canvas, so it clips nothing away."
  [children]
  {:type "lv_obj" :coords [0 0 399 399] :children children})

;; Two boxes 4px apart vertically: [10..29] and [34..53]. `separation` is
;; inclusive-coords aware, so 34 - 29 - 1 = 4.
(def ^:private slider-node
  {:type "lv_slider" :uid 1 :coords [10 10 110 29] :children []})
(def ^:private neighbour
  {:type "lv_label" :uid 2 :coords [10 34 110 53] :children []})

(defn- with-paint
  "The slider, painting 6px above and below its own box — the measured
   overhang of a stock 6px LV_PART_KNOB pad on this corpus' slider cards."
  [node]
  (assoc node :paint_box [(nth (:coords node) 0)
                          (- (nth (:coords node) 1) 6)
                          (nth (:coords node) 2)
                          (+ (nth (:coords node) 3) 6)]))

(deftest paint-only-overhang-fires-where-the-node-boxes-are-clear
  (testing "the case no node-box rule can see: boxes 4px apart, paint 2px into"
    (let [fs (judge (root-with [(with-paint slider-node) neighbour]))]
      (is (= 1 (count (crowding fs))))
      (is (= "lv_slider#1 vs lv_label#2" (:node (first (crowding fs)))))
      (is (re-find #"the layout left 4px" (str (:detail (first (crowding fs))))))
      (is (re-find #"OVERLAP by 2px" (str (:detail (first (crowding fs))))))))
  (testing "CONTROL — the identical geometry with no paint key is SILENT, which
            is what makes the case above a statement about paint and not about
            the boxes"
    (is (empty? (judge (root-with [slider-node neighbour]))))))

(deftest a-pair-the-layout-itself-placed-tight-is-not-this-rules-business
  (testing "abutting node boxes are excluded however far the paint reaches —
            the clause that keeps tab bars and stacked rows out of the lane"
    (let [abutting (assoc neighbour :coords [10 30 110 49])]
      (is (empty? (judge (root-with [(with-paint slider-node) abutting]))))))
  (testing "CONTROL — the same two nodes moved to a 4px gutter DO fire, so the
            exclusion above is about the gutter and not about these nodes"
    (is (= 1 (count (crowding (judge (root-with [(with-paint slider-node)
                                                 neighbour]))))))))

;; The ancestor/descendant case is REACHABLE ONLY THROUGH OVERFLOW_VISIBLE, and
;; the first version of this test did not reach it — a mutation deleting the
;; `related?` clause outright left the whole suite GREEN. The reason is worth
;; keeping, because it is not obvious and it constrains any future case here:
;; a descendant is clipped to its ancestors' boxes, so its surviving box is a
;; SUBSET of the ancestor's and the two therefore intersect — which the layout
;; guard (node-sep >= gap-px) already excludes, before `related?` is consulted.
;; The one shape that escapes is an ancestor whose child clip is LARGER than
;; its own box: under OVERFLOW_VISIBLE a child may sit inside the clip and
;; wholly outside the parent's coords, leaving the two genuinely apart and the
;; pair genuinely judged. That is the case below.
(def ^:private overflow-parent
  {:type "lv_obj" :uid 3 :coords [10 20 110 39] :descend_gate [10 0 110 59]
   :children []})

(def ^:private escaping-child
  {:type "lv_label" :uid 4 :coords [10 4 110 12] :paint_box [10 4 110 18]
   :children []})

(deftest containment-is-composition-not-crowding
  (testing "a child sitting outside its parent's box but inside the parent's
            OVERFLOW_VISIBLE clip is APART from it and still excluded, because
            an ancestor and its descendant are one composition"
    (let [tree (root-with [(assoc overflow-parent
                                  :children [escaping-child])])]
      (is (empty? (crowding (judge tree))))))
  (testing "CONTROL — the identical two boxes as unrelated SIBLINGS fire, which
            is what makes the silence above attributable to `related?` and not
            to the geometry, the clip, or an empty candidate set"
    (let [tree (root-with [overflow-parent escaping-child])
          fs (crowding (judge tree))]
      (is (= 1 (count fs)))
      (is (re-find #"the layout left 7px" (:detail (first fs)))))))

(deftest a-node-that-is-not-drawn-crowds-nothing
  (testing "hidden"
    (is (empty? (judge (root-with [(assoc (with-paint slider-node)
                                          :hidden true)
                                   neighbour])))))
  (testing "hidden under an ancestor — the subtree LVGL never reaches"
    (is (empty? (judge {:type "lv_obj" :coords [0 0 399 399] :hidden true
                        :children [(with-paint slider-node) neighbour]}))))
  (testing "snapped out of view by the tabview carousel"
    ;; child 1 of an lv_tabview is the content; a direct child of it whose box
    ;; misses the content box is a snapped page.
    (let [tree {:type "lv_tabview" :coords [0 0 399 399]
                :children [{:type "lv_obj" :coords [0 0 399 9] :children []}
                           {:type "lv_obj" :coords [0 10 399 399]
                            :children [{:type "lv_obj" :coords [900 900 999 999]
                                        :children [(with-paint slider-node)
                                                   neighbour]}]}]}]
      (is (empty? (crowding (judge tree))))))
  (testing "CONTROL — each of the three fires once the property is removed"
    (is (= 1 (count (crowding (judge (root-with [(with-paint slider-node)
                                                 neighbour]))))))))

(deftest an-ancestor-clip-that-cuts-the-overhang-away-earns-no-finding
  (testing "the overhang escapes the parent, so refr_obj never paints it"
    ;; The parent's box stops at y=29, so the slider's 6px downward overhang is
    ;; clipped off and cannot reach the neighbour below it.
    (let [tree {:type "lv_obj" :coords [0 0 399 399]
                :children [{:type "lv_obj" :coords [10 10 110 29]
                            :children [(with-paint slider-node)]}
                           neighbour]}]
      (is (empty? (crowding (judge tree))))))
  (testing "CONTROL — the SAME tree fires once that ancestor carries the
            OVERFLOW_VISIBLE gate the dump reports as descend_gate, which is
            the box refr_obj then clips children to"
    (let [tree {:type "lv_obj" :coords [0 0 399 399]
                :children [{:type "lv_obj" :coords [10 10 110 29]
                            :descend_gate [10 4 110 35]
                            :children [(with-paint slider-node)]}
                           neighbour]}]
      (is (= 1 (count (crowding (judge tree))))))))

(deftest an-unresolved-extent-is-reported-as-unknown-never-as-clean
  (testing "a bound that is NOT clear of its neighbour is :unmeasurable, and is
            deliberately not :paint-crowding — the bound cannot assert one"
    (let [bounded (assoc slider-node :paint_bound [10 4 110 35])
          fs (judge (root-with [bounded neighbour]))]
      (is (empty? (crowding fs)))
      (is (= 1 (count (unmeasurable fs))))
      (is (re-find #"could not be resolved exactly"
                   (:detail (first (unmeasurable fs)))))))
  (testing "a bound that IS clear of its neighbour proves a negative and is
            silent — the direction a conservative box can be trusted in"
    (let [bounded (assoc slider-node :paint_bound [10 9 110 30])]
      (is (empty? (judge (root-with [bounded neighbour]))))))
  (testing "CONTROL — the same overhang as an EXACT box is crowding, so the
            split above is about resolution and not about the geometry"
    (is (= 1 (count (crowding (judge (root-with [(with-paint slider-node)
                                                 neighbour]))))))))

(deftest a-node-with-no-coords-is-a-finding-not-a-silent-drop
  (let [fs (judge (root-with [{:type "lv_label" :children []} neighbour]))]
    (is (= 1 (count (unmeasurable fs))))
    (is (re-find #"no :coords" (:detail (first (unmeasurable fs)))))))

(deftest the-threshold-is-honoured-in-both-directions
  (testing "gap-px 0 does NOT disable the rule — it is the strictest-NEGATIVE
            setting, where a painted OVERLAP still fires because the layout
            guard (node-sep >= 0) excludes only pairs whose boxes already
            intersect. Pinned because the threshold's :doc asserted the
            opposite until this assertion refuted it"
    (is (= 1 (count (crowding (judge (root-with [(with-paint slider-node)
                                                 neighbour]) 0))))))
  (testing "and at 0 a pair that merely TOUCHES in paint is silent, which is
            what makes 0 the strict-overlap setting rather than a disable"
    (let [touching (assoc slider-node :paint_box [10 10 110 33])]
      (is (empty? (crowding (judge (root-with [touching neighbour]) 0))))))
  (testing "a 4px gutter is out of scope at gap-px 8, because the layout never
            allocated that much — the rule reports paint eating clearance, not
            clearance that was never there"
    (is (empty? (judge (root-with [(with-paint slider-node) neighbour]) 8))))
  (testing "CONTROL — at gap-px 3 that same pair fires"
    (is (= 1 (count (crowding (judge (root-with [(with-paint slider-node)
                                                 neighbour]) 3)))))))

(deftest the-producer-registers-and-declares-what-it-reads
  (is (= :spacing (:id spacing/producer)))
  (is (= #{:nodes} (:requires spacing/producer)))
  (testing "two-way by construction — UI-QUALITY-CONTRACTS §0's `exact` row.
            Declaring :outcomes here would let a later diff soften a finding to
            :cantTell, and validate-producers! is what refuses it"
    (is (nil? (:outcomes spacing/producer))))
  (testing "its gap-px is its OWN. Both producers declare a plain :gap-px, and
            the registry keys them by producer id — so supplying one cannot
            silently move the other. Asserted through the real resolver rather
            than by reading the two maps, because shadowing is a property of
            resolution and would not show up in either declaration"
    (let [resolved (findings/resolve-thresholds
                    [overlap/producer spacing/producer]
                    (merge lanes/overlap-thresholds {:spacing/gap-px 5}))]
      (is (= 0 (get-in resolved [:overlap :gap-px])))
      (is (= 5 (get-in resolved [:spacing :gap-px])))))
  (testing "and its declared default is what the unsupplied case resolves to"
    (let [resolved (findings/resolve-thresholds [spacing/producer] {})]
      (is (= (:default (:gap-px (:thresholds spacing/producer)))
             (get-in resolved [:spacing :gap-px]))))))

;; ── OVERFLOW PADDING — the budget, and the two rules over inflated boxes ──
;; The renderer computes `overflow_padding` and the paint keys from the same
;; object, so a real render cannot exhibit a paint escaping its own budget:
;; the violating pair is constructible only here. That is the division of
;; labour the canary's docstring already draws — this file pins the reducer's
;; verdicts, the canary pins that the vocabulary exists and that the renderer
;; agrees with the rule on a real widget.

(defn- escapes [fs] (filterv #(= :paint-escapes-budget (:invariant %)) fs))

(deftest overflow-padding-defaults-to-zero-and-inflates-nothing
  (testing "an absent key reads as [0 0 0 0], so the budget box IS :coords and
            both rules are exactly the node-box rules the corpus already ran"
    (is (= [0 0 0 0] (spacing/overflow-padding neighbour)))
    (is (= (:coords neighbour) (spacing/budget-box neighbour))))
  (testing "and a present one inflates per SIDE, not by one reach — the shape
            the slider measurement forced: 6px across the track, 16 along it"
    (is (= [-6 4 126 35]
           (spacing/budget-box (assoc slider-node
                                      :overflow_padding [16 6 16 6]))))))

(deftest rule-1-containment-fires-where-the-paint-outruns-its-own-budget
  (testing "an exactly-resolved paint reaching outside inflate(coords, pad) is
            a finding on its own, with no neighbour involved — a widget
            painting into space nothing allocated is wrong on an empty screen"
    (let [over (assoc (with-paint slider-node) :overflow_padding [0 2 0 2])
          fs (judge (root-with [over]))]
      (is (= 1 (count (escapes fs))))
      (is (re-find #"not inside the budgeted" (str (:detail (first (escapes fs))))))))
  (testing "CONTROL — the identical paint under a budget that covers it is
            silent, so the clause is about the budget and not about the
            overhang existing"
    (let [ok (assoc (with-paint slider-node) :overflow_padding [0 6 0 6])]
      (is (empty? (escapes (judge (root-with [ok])))))))
  (testing "a BOUND never fires it: the bound says where the paint cannot have
            gone, which asserts nothing about where it did"
    (let [bounded (assoc slider-node
                         :paint_bound [10 4 110 35]
                         :overflow_padding [0 2 0 2])]
      (is (empty? (escapes (judge (root-with [bounded])))))))
  (testing "and an UNBUDGETED node never fires it either — absence is 'no
            budget resolved', so reading it as a zero budget would report
            every unresolved overhang as an escape"
    (is (empty? (escapes (judge (root-with [(with-paint slider-node)])))))))

(deftest rule-2-judges-the-INFLATED-box-not-this-renders-pixels
  (testing "a budget wider than the paint takes the clearance: the slider
            draws nothing into the gutter here, and is entitled to 6px of it"
    (let [claiming (assoc slider-node :overflow_padding [0 6 0 6])
          fs (judge (root-with [claiming neighbour]))]
      (is (= 1 (count (crowding fs))))
      (is (re-find #"This render draws 4px of clearance"
                   (str (:detail (first (crowding fs))))))))
  (testing "CONTROL — the same two nodes with no budget are silent, because
            their DRAWN boxes are 4px apart and the layout allocated 4"
    (is (empty? (judge (root-with [slider-node neighbour])))))
  (testing "the LAYOUT half still keys the rule: a pair the author placed
            flush is out of scope however much either claims"
    (let [flush-nb (assoc neighbour :coords [10 30 110 53])
          claiming (assoc slider-node :overflow_padding [0 6 0 6])]
      (is (empty? (judge (root-with [claiming flush-nb]))))))
  (testing "a budget reaches BOTH ways along its axis, which is the property a
            snapshot cannot show: the same claim fires against a neighbour
            ABOVE, where this render painted nothing at all"
    (let [claiming (assoc slider-node :overflow_padding [0 6 0 6])
          above {:type "lv_label" :uid 3 :coords [10 -15 110 5] :children []}
          fs (judge (root-with [claiming above]))]
      (is (= 1 (count (crowding fs))))
      (is (re-find #"This render draws 4px of clearance"
                   (str (:detail (first (crowding fs)))))))))
