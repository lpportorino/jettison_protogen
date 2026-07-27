(ns devcards.invariants-test
  "Unit tests for the DOM invariant lane (`devcards.invariants`) — pure
   dump-tree maps in, findings out (no wasm, no host).

   The focus is the designed-geometry exclusion set: each exclusion must
   silence exactly the LVGL contract it names and NOTHING else, so every
   exemption case here is paired with a CONTROL that must still fire. An
   exemption test with no control cannot tell 'correctly exempted' from
   'the lane returned nothing at all'."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

;; ── the snapped-page exemption's scope ──────────────────────────────────────
;; The snapped-page exclusion is :clipped-only (plus :zero-visible-area on its
;; own lane). :offscreen is NOT exempt: a page outside the carousel's content
;; box has not thereby been shown to be off the DISPLAY, which is what
;; obj_offscreen measures. The C side suppresses that case first anyway —
;; obj_in_scroll_region returns true for a SNAPPABLE child under a snap
;; container — so the lane never sees it in practice.
(def ^:private snapped-page-tree
  "An lv_tabview whose content (child index 1) holds TWO pages: one snapped
   fully out of view — its box does not intersect the content box — and one
   ACTIVE page that does intersect. Both carry the same flags. Neither is
   hidden, so only the snapped-page rule can exempt anything here, and the
   active page is what proves the rule stays narrow."
  {:type "lv_tabview"
   :uid 1
   :coords [0 0 99 99]
   :children [{:type "lv_obj" :uid 2 :coords [0 0 99 20] :children []}
              {:type "lv_obj"
               :uid 3
               :coords [0 20 99 99]
               :children [{:type "lv_obj"
                           :uid 4
                           :coords [-500 -500 -400 -400]
                           :clipped true
                           :offscreen true
                           :children []}
                          {:type "lv_obj"
                           :uid 5
                           :coords [0 20 99 99]
                           :clipped true
                           :offscreen true
                           :children []}]}]})

(defn- nodes-of
  "Node labels of every finding carrying `flag`."
  [findings flag]
  (set (for [f findings :when (= flag (:invariant f))] (:node f))))

(deftest snapped-page-clipped-is-designed-geometry
  (let [findings (inv/tree-findings "snapped-card" snapped-page-tree caps)]
    (testing "a snapped-away page's :clipped is exempt — the docstring's
              sanctioned case"
      (is (not (contains? (nodes-of findings :clipped) "lv_obj#4"))))
    (testing "CONTROL: the ACTIVE page, which intersects the content box,
              is NOT exempt and its identical :clipped still fires — the
              exemption keys on being snapped away, not on being a page"
      (is (contains? (nodes-of findings :clipped) "lv_obj#5")))
    (testing "the snapped page's :offscreen is NOT exempt: obj_offscreen
              measures against the DISPLAY, and leaving the carousel's content
              box says nothing about that"
      (is (contains? (nodes-of findings :offscreen) "lv_obj#4")))))

;; ── the exemption shape and its matching axes ───────────────────────────
;; The closed key set had NO canary at all before the ACT axes widened it,
;; which is the one state in which widening a closed set is free. These pin
;; the set, then each new axis, each against a control that must stay live.

(def ^:private legacy-exemption
  {:card "c" :invariant :contrast
   :rationale "proven benign for this card"
   :retires-when "the widget grows its own box"})

(defn- live-of
  [findings exemptions]
  (set (map (juxt :act/outcome :act/test-mode :act/reason :node)
            (:live (inv/apply-exemptions findings exemptions)))))

(defn- msg
  "The message of whatever `f` throws, or nil. `thrown?` alone cannot tell a
   clause from its neighbour, which is how a decorative canary survives."
  [f]
  (try (f) nil (catch Throwable t (ex-message t))))

(deftest the-exemption-key-set-is-CLOSED
  (testing "an unknown key must be refused rather than ignored: an entry
            carrying a knob nothing reads looks armed and does nothing.
            :severity is the plausible neighbour — it is the axis this repo
            deliberately does NOT have.
            REVERT-TO-BREAK: add :severity to `exemption-keys`."
    (is (str/includes?
         (str (msg #(inv/validate-exemptions!
                     [(assoc legacy-exemption :severity :minor)])))
         "unknown keys [:severity]")))
  (testing "and the three ACT axes are IN it — the first draft omitted
            :act/test-mode, which made a manual-only exemption unwritable and
            therefore made the hole below unfixable at the config layer"
    (is (inv/validate-exemptions!
         [(assoc legacy-exemption :act/test-mode :manual)])))
  (testing "CONTROL: the same entry without the unknown key validates, so the
            throw keys on the key and not on the entry shape"
    (is (= [legacy-exemption] (inv/validate-exemptions! [legacy-exemption])))))

(deftest a-legacy-exemption-still-exempts-a-legacy-finding
  (testing "the compat pin: every exemption ever written here predates the
            outcome axis, so absent must keep meaning what it meant"
    (is (empty? (live-of [{:card "c" :invariant :contrast :node "n"}]
                         [legacy-exemption]))))
  (testing "and the UNNAMESPACED words on a finding are payload the matcher
            does not read, so a consumer producer that used :reason for its
            own purposes keeps the exemption written for it"
    (is (empty? (live-of [{:card "c" :invariant :contrast :node "n"
                           :reason "overflows its box" :outcome "hand-set"}]
                         [legacy-exemption])))))

(deftest a-failed-exemption-does-NOT-swallow-another-outcome
  (testing "without the outcome conjunct, an entry written for one verdict
            silences the other on the same card and invariant — and the stale
            ratchet cannot catch it, because the entry is still matching: its
            own retirement condition is the event that turns it into a
            defect-hider.

            The surviving finding is REASON-FREE on purpose. Measured: with a
            :cantTell + reason finding, deleting the outcome conjunct leaves
            this green, because the reason conjunct separates them for its own
            unrelated reason. Only the outcome differs below.
            REVERT-TO-BREAK: delete the `finding-outcome` conjunct."
    (is (= #{[:untested nil nil "n"]}
           (live-of [{:card "c" :invariant :contrast :act/outcome :untested
                      :node "n"}]
                    [legacy-exemption]))))
  (testing "CONTROL: the matching :failed finding IS exempted by that same
            entry in the same call, so the survivor above is the outcome axis
            and not a dead exemption"
    (is (= #{[:untested nil nil "n"]}
           (live-of [{:card "c" :invariant :contrast :node "n"}
                     {:card "c" :invariant :contrast :act/outcome :untested
                      :node "n"}]
                    [legacy-exemption])))))

(deftest a-VLM-exemption-does-NOT-swallow-the-DETERMINISTIC-finding
  (testing "the silent skip this whole axis exists to prevent. The VLM review
            is :manual and rides this same vector; a deterministic lane on
            the same card and invariant is :automatic. With no mode conjunct
            an entry dispositioning the VLM finding also swallowed the
            :failed deterministic one that shares its (card, invariant,
            outcome) — into :exempted, so the run was byte-identical to a
            clean one, and the stale ratchet stayed quiet because the entry
            was still matching something.
            REVERT-TO-BREAK: delete the `finding-mode` conjunct."
    (let [vlm-entry (assoc legacy-exemption :act/test-mode :manual)
          fs [{:card "c" :invariant :contrast :act/test-mode :manual :node "n"}
              {:card "c" :invariant :contrast :node "n"}]]
      (is (= #{[nil nil nil "n"]} (live-of fs [vlm-entry])))))
  (testing "CONTROL: the same entry DOES exempt the manual finding it was
            written for, so the survivor above is the mode axis and not a
            dead entry"
    (is (empty? (live-of [{:card "c" :invariant :contrast
                           :act/test-mode :manual :node "n"}]
                         [(assoc legacy-exemption :act/test-mode :manual)]))))
  (testing "and symmetrically: a legacy four-key entry does not silently
            change meaning the moment a :manual producer is armed"
    (is (= #{[nil :manual nil "n"]}
           (live-of [{:card "c" :invariant :contrast
                      :act/test-mode :manual :node "n"}]
                    [legacy-exemption])))))

(deftest a-non-answer-exemption-is-scoped-to-ONE-reason
  (testing "an exemption for 'we cannot measure the bitmap gauges' must not
            also swallow 'the mask emitter failed'.
            REVERT-TO-BREAK: delete the :act/reason conjunct."
    (let [e (assoc legacy-exemption :act/outcome :cantTell
                   :act/reason :noise-band)
          fs [{:card "c" :invariant :contrast :act/outcome :cantTell
               :act/reason :noise-band :node "n"}
              {:card "c" :invariant :contrast :act/outcome :cantTell
               :act/reason :mask-absent :node "n"}]]
      (is (= #{[:cantTell nil :mask-absent "n"]} (live-of fs [e]))))))

(deftest the-node-axis-narrows-and-its-absence-does-not
  (let [fs [{:card "c" :invariant :contrast :node "btn#1"}
            {:card "c" :invariant :contrast :node "label#2"}]]
    (testing "an absent :node matches ANY node — exactly what an exemption
              did before the axis existed"
      (is (empty? (live-of fs [legacy-exemption]))))
    (testing "and a supplied one is a FULL-match regex, like :card: the named
              node is exempted and the other stays live.
              REVERT-TO-BREAK: delete the :node conjunct."
      (is (= #{[nil nil nil "label#2"]}
             (live-of fs [(assoc legacy-exemption :node "btn#.*")]))))
    (testing "full-match, not substring — a pattern that matches a prefix
              only must exempt nothing.
              REVERT-TO-BREAK: `re-find` for `re-matches` on the :node conjunct."
      (is (= 2 (count (live-of fs [(assoc legacy-exemption :node "btn")])))))))

(deftest the-exemption-axis-rules-mirror-the-finding-rules
  (testing "an outcome that owes a reason owes one here too.
            REVERT-TO-BREAK: delete the reasoned-outcome branch."
    (doseq [o [:cantTell :inapplicable :untested]]
      (is (str/includes?
           (str (msg #(inv/validate-exemptions!
                       [(assoc legacy-exemption :act/outcome o)])))
           "owes an :act/reason keyword")
          (str o))))
  (testing "and nothing else may carry one"
    (is (str/includes?
         (str (msg #(inv/validate-exemptions!
                     [(assoc legacy-exemption :act/reason :noise-band)])))
         ":act/reason without an :act/outcome in")))
  (testing "an outcome outside the ACT vocabulary is refused"
    (is (str/includes?
         (str (msg #(inv/validate-exemptions!
                     [(assoc legacy-exemption :act/outcome :cantTel)])))
         ":act/outcome must be one of")))
  (testing "and :passed is refused too: a finding may never carry it, so an
            entry naming it would be stale from birth"
    (is (str/includes?
         (str (msg #(inv/validate-exemptions!
                     [(assoc legacy-exemption :act/outcome :passed)])))
         "stale from birth")))
  (testing "an out-of-vocabulary MODE is refused — the axis the first draft
            could not even name"
    (is (str/includes?
         (str (msg #(inv/validate-exemptions!
                     [(assoc legacy-exemption :act/test-mode :semi-auto)])))
         ":act/test-mode must be one of")))
  (testing "and a non-string :node is refused. NOT because `re-pattern` would
            reject it — it returns a Pattern unchanged, so a #\"…\" literal
            would match perfectly well, and the first draft's stated
            justification was refutable in one line. Because the exemption
            list is DATA: committed, reviewed and round-tripped through EDN,
            where a compiled Pattern does not survive as itself.
            REVERT-TO-BREAK: delete the :node string check."
    (is (str/includes?
         (str (msg #(inv/validate-exemptions!
                     [(assoc legacy-exemption :node #"btn.*")])))
         "does not round-trip")))
  (testing "CONTROL: the well-formed narrowed entry validates, so each throw
            keys on its own clause"
    (is (inv/validate-exemptions!
         [(assoc legacy-exemption :act/outcome :cantTell
                 :act/reason :noise-band :act/test-mode :manual
                 :node "btn#.*")]))))
