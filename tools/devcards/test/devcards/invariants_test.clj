(ns devcards.invariants-test
  "Unit tests for the DOM invariant lane (`devcards.invariants`) — pure
   dump-tree maps in, findings out (no wasm, no host).

   The focus is the designed-geometry exclusion set: each exclusion must
   silence exactly the LVGL contract it names and NOTHING else, so every
   exemption case here is paired with a CONTROL that must still fire. An
   exemption test with no control cannot tell 'correctly exempted' from
   'the lane returned nothing at all'."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.invariants :as inv]))

(def ^:private caps
  "vis_px is expressible — the lane must not silently no-op."
  {:vis-px? true})

(defn- flags-of
  "The invariant keywords findings carry, as a set."
  [findings]
  (set (map :invariant findings)))

(defn- clipped-nodes
  "Node labels of every :clipped finding."
  [findings]
  (set (for [f findings :when (= :clipped (:invariant f))] (:node f))))

;; ── hidden geometry ─────────────────────────────────────────────────────
;; A hidden object is NOT layout-positioned (lv_obj_pos.c
;; lv_obj_is_layout_positioned), so it is self-placed at its parent's
;; content origin at its own LV_SIZE_CONTENT size; and the parent's
;; calc_content_width/height SKIP hidden children, so the parent never
;; grows to fit it. Its box is therefore self-derived and structurally
;; unrelated to the parent's extent — the exact geometry obj_clipped
;; compares. It draws nothing (vis_px 0), and when SHOWN the parent
;; recomputes LV_SIZE_CONTENT and grows, so it is not clipped then either.

(def ^:private hidden-overflow-tree
  "A hidden child whose self-derived box escapes its parent's content box,
   beside a VISIBLE sibling with the identical overflow. Only the visible
   one is a real defect."
  {:type "lv_obj"
   :uid 1
   :coords [0 0 99 99]
   :children [{:type "lv_obj"
               :uid 2
               :coords [9 9 199 199]
               :hidden true
               :clipped true
               :vis_px 0
               :children []}
              {:type "lv_obj"
               :uid 3
               :coords [9 9 199 199]
               :clipped true
               :children []}]})

(deftest hidden-node-clipped-is-designed-geometry
  (let [findings (inv/tree-findings "hidden-card" hidden-overflow-tree caps)]
    (testing "a HIDDEN node's :clipped is exempt — its box is self-derived,
              never a statement about the parent's extent"
      (is (not (contains? (clipped-nodes findings) "lv_obj#2"))))
    (testing "CONTROL: the visible sibling with the IDENTICAL box still fires
              — the exemption keys on hidden, not on the geometry"
      (is (contains? (clipped-nodes findings) "lv_obj#3")))
    (testing "the hidden node's vis_px 0 stays exempt too (pre-existing lane)"
      (is (not-any? #(= "lv_obj#2" (:node %))
                    (filter #(= :zero-visible-area (:invariant %)) findings))))))

(deftest hidden-node-offscreen-still-fires
  (let [tree {:type "lv_obj"
              :uid 1
              :coords [0 0 99 99]
              :children [{:type "lv_obj"
                          :uid 2
                          :coords [-500 -500 -400 -400]
                          :hidden true
                          :offscreen true
                          :children []}
                         {:type "lv_obj"
                          :uid 3
                          :coords [-500 -500 -400 -400]
                          :offscreen true
                          :children []}]}
        findings (inv/tree-findings "offscreen-card" tree caps)
        offscreen-nodes (set (for [f findings
                                   :when (= :offscreen (:invariant f))]
                               (:node f)))]
    (testing "a HIDDEN node's :offscreen is NOT exempt — obj_offscreen compares
              the box against the DISPLAY rectangle, and its one ancestor test
              (obj_in_scroll_region) keys on scroll/snap, never on the parent's
              content-box sizing, so the hidden-node proof — which is entirely
              about that sizing — does not reach it. A parent growing cannot
              move a node back on-screen."
      (is (contains? offscreen-nodes "lv_obj#2")))
    (testing "CONTROL: the visible sibling still fires"
      (is (contains? offscreen-nodes "lv_obj#3")))))

(deftest descendant-of-hidden-node-is-designed-geometry
  (testing "a subtree under a hidden ancestor inherits meaningless geometry
            RELATIVE TO ITS PARENT — the ancestor is self-placed, so its
            children's boxes are too, and :clipped is exempt. :offscreen is
            not: it is measured against the display, which the ancestor's
            self-placement says nothing about."
    (let [tree {:type "lv_obj"
                :uid 1
                :coords [0 0 99 99]
                :children [{:type "lv_obj"
                            :uid 2
                            :coords [9 9 199 199]
                            :hidden true
                            :children [{:type "lv_label"
                                        :uid 4
                                        :coords [9 9 400 400]
                                        :clipped true
                                        :offscreen true
                                        :children []}]}]}
          findings (inv/tree-findings "hidden-under-card" tree caps)]
      (is (= #{:offscreen} (flags-of findings))))))

;; ── the exemption stays NARROW ──────────────────────────────────────────
;; Every guard below is a regression the hidden rule must not swallow.

(deftest visible-tree-defects-still-fire
  (testing "the hidden exemption does not blanket-suppress the lane"
    (let [tree {:type "lv_obj"
                :uid 1
                :coords [0 0 99 99]
                :children [{:type "lv_obj"
                            :uid 2
                            :coords [9 9 199 199]
                            :clipped true
                            :squished true
                            :children []}
                           {:type "lv_label"
                            :uid 3
                            :coords [9 9 9 8]
                            :text_clipped true
                            :children []}]}
          findings (inv/tree-findings "visible-card" tree caps)]
      (is (= #{:clipped :squished :text_clipped :zero-area} (flags-of findings))))))

(deftest hidden-does-not-exempt-non-geometry-flags
  (testing "the exemption covers :clipped only — a hidden node that still
            reports overflow/truncation is NOT silenced"
    (let [tree {:type "lv_obj"
                :uid 1
                :coords [0 0 99 99]
                :children [{:type "lv_label"
                            :uid 2
                            :coords [9 9 50 50]
                            :hidden true
                            :text_truncated true
                            :overflow true
                            :children []}]}
          findings (inv/tree-findings "hidden-nongeom-card" tree caps)]
      (is (= #{:text_truncated :overflow} (flags-of findings))))))

(deftest hidden-does-not-mask-a-truncated-dump
  (testing "an incomplete dump is unjudgeable regardless of hidden nodes"
    (let [tree {:type "lv_obj"
                :uid 1
                :coords [0 0 99 99]
                :truncated true
                :children [{:type "lv_obj"
                            :uid 2
                            :coords [9 9 199 199]
                            :hidden true
                            :clipped true
                            :children []}]}
          findings (inv/tree-findings "truncated-card" tree caps)]
      (is (contains? (flags-of findings) :dump-truncated)))))

;; ── the :zero-visible-area lane is ALIVE, and DISCRIMINATES ────────────────
;; Every other reference to this lane in this ns is an ABSENCE assertion — it
;; checks that a hidden / snapped node is EXEMPT. An absence assertion cannot
;; tell "the exemption works" from "the lane never emits at all": both produce
;; an empty set. That is the pass-value-equals-nothing-ran-value shape, and the
;; `caps` docstring above claims "the lane must not silently no-op" while
;; nothing verified it.
;;
;; Firing is only half of it. A lane that emitted on EVERY node carrying vis_px
;; would also satisfy a bare positive assertion — and that is the loud failure
;; here, not a theoretical one: the renderer emits 0 < vis_px < total for every
;; partially-clipped node, so a degraded discriminator turns the whole corpus
;; into false findings. Hence the non-zero sibling below: the lane must fire on
;; 0 and stay silent on 5.
(def ^:private occluded-tree
  "One genuinely occluded node (vis_px 0) and one merely PARTIALLY clipped
   sibling (vis_px 5). Neither is hidden, under a hidden ancestor, or snapped
   away, so nothing exempts either — only the zero discriminates. Shared by
   both assertions below so 'the same tree' is structural, not clerical: a
   hand-duplicated literal would let an edit to one copy silently retire the
   control."
  {:type "lv_obj"
   :uid 1
   :coords [0 0 99 99]
   :children [{:type "lv_obj"
               :uid 2
               :coords [9 9 49 49]
               :vis_px 0
               :children []}
              {:type "lv_obj"
               :uid 3
               :coords [9 9 49 49]
               :vis_px 5
               :children []}]})

(deftest zero-visible-area-fires-on-an-occluded-node
  (let [findings (inv/tree-findings "occluded-card" occluded-tree caps)
        zva (set (for [f findings
                       :when (= :zero-visible-area (:invariant f))]
                   (:node f)))]
    (testing "a node with vis_px 0 that nothing exempts IS a genuine occlusion
              finding"
      (is (contains? zva "lv_obj#2")))
    (testing "DISCRIMINATOR: the sibling with vis_px 5 — partially clipped, the
              renderer's normal case — must NOT fire. Without this the lane
              could emit on every node carrying vis_px and still look correct."
      (is (not (contains? zva "lv_obj#3"))))
    (testing "and nothing else in this tree fires: the assertions above cannot
              be satisfied by some other lane"
      (is (= #{:zero-visible-area} (flags-of findings))))))

(deftest zero-visible-area-is-capability-gated
  (testing "CONTROL: the SAME tree judged by a module that cannot express
            vis_px yields nothing — so the assertions above are keyed on the
            declared capability, not on the geometry"
    (let [findings (inv/tree-findings "occluded-card" occluded-tree {:vis-px? false})]
      (is (not (contains? (flags-of findings) :zero-visible-area))))))
