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

(deftest hidden-node-offscreen-is-designed-geometry
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
    (testing ":offscreen follows the same rule as :clipped for a hidden node"
      (is (not (contains? offscreen-nodes "lv_obj#2"))))
    (testing "CONTROL: the visible sibling still fires"
      (is (contains? offscreen-nodes "lv_obj#3")))))

(deftest descendant-of-hidden-node-is-designed-geometry
  (testing "a subtree under a hidden ancestor inherits meaningless geometry
            — the ancestor is self-placed, so its children's boxes are too"
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
      (is (empty? (flags-of findings))))))

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
  (testing "the exemption covers :clipped/:offscreen only — a hidden node
            that still reports overflow/truncation is NOT silenced"
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
