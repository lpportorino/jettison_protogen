(ns devcards.overlap-test
  "Canaries for the overlap rule (`devcards.overlap`) — pure dump-tree maps
   in, findings out.

   Every positive case here is paired with a CONTROL that must stay
   silent, and — the harder half — every exclusion is paired with a case
   that must still FIRE. A rule can go red for the wrong reason and look
   exactly as correct as one that went red for the right one, so the
   nesting control below is not a nicety: it is the specific defect that
   a naive pairwise geometry check reproduces first."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.overlap :as overlap]))

(def ^:private table
  "Controls are interactive, chrome and text are not."
  {:types {"lv_obj" {:interactive? false :role :structural}
           "lv_button" {:interactive? true :role :interactive}
           "lv_switch" {:interactive? true :role :interactive}
           "lv_label" {:interactive? false :role :text}}})

(defn- judge
  "Run the rule over `root` with `table`, at `gap-px`."
  ([root] (judge root table 0))
  ([root classes gap-px]
   (overlap/findings {:card-id "c"
                      :nodes (invariants/annotate-tree root)
                      :classes classes
                      :thresholds {:gap-px gap-px}})))

(defn- invariants-of [fs] (set (map :invariant fs)))

(defn- root-with
  [children]
  {:type "lv_obj" :coords [0 0 199 199] :children children})

;; ── the canary: two controls in the same place ──────────────────────────

(def ^:private overlapping-controls
  "Two sibling buttons sharing a 20px-wide column."
  (root-with [{:type "lv_button" :uid 1 :coords [10 10 59 39] :children []}
              {:type "lv_button" :uid 2 :coords [40 10 89 39] :children []}]))

(deftest overlapping-enabled-controls-fire
  (let [fs (judge overlapping-controls)]
    (testing "two independently-placed interactive elements sharing pixels
              is the defect this rule exists for"
      (is (= #{:overlap} (invariants-of fs)))
      (is (= 1 (count fs)))
      (is (= "lv_button#1 vs lv_button#2" (:node (first fs)))))
    (testing "the detail names the overlap depth, so a reader can tell a
              1px seam from a fully stacked pair"
      (is (re-find #"overlap depth 20px" (:detail (first fs)))))))

(deftest a-disabled-participant-does-NOT-exempt-the-overlap
  (testing "LV_STATE_DISABLED does not remove a widget from the pointer
            path: lv_obj_hit_test gates on LV_OBJ_FLAG_CLICKABLE alone
            (lv_obj_pos.c:1201) and lv_indev_search_obj skips only HIDDEN
            (lv_indev.c:623), so a disabled widget is still claimed as
            indev_obj_act and DISABLED only suppresses the press EVENT
            (lv_indev.c:1339). This renderer's enabled_when binding toggles
            the state and never clears CLICKABLE (renderer/src/renderer.c
            :1722-1750). A disabled control over an enabled one therefore
            absorbs the press and DROPS it — the silent version of the bug.
            Exempting it would bless a dead zone."
    (let [disabled-top (root-with
                        [{:type "lv_button" :uid 1 :coords [10 10 59 39] :children []}
                         {:type "lv_button"
                          :uid 2
                          :coords [40 10 89 39]
                          :disabled true
                          :children []}])
          fs (judge disabled-top)]
      (is (= #{:overlap} (invariants-of fs)))
      (testing "and the detail says WHY it is the worse case, not merely that
                it overlaps"
        (is (re-find #"lv_button#2 is DISABLED" (:detail (first fs))))
        (is (re-find #"absorbs the press" (:detail (first fs))))))))

;; ── the exclusions, each with a case that must still fire ────────────────

(deftest nesting-is-not-an-overlap
  (testing "a control inside an interactive container overlaps it by
            construction — containment is how composition works. A rule
            without this reports every button against its own parent and
            goes red for nesting rather than for the hazard, which is a
            false gate that happens to be the right colour."
    (let [scrollable {:types (assoc (:types table)
                                    "lv_obj"
                                    {:interactive? true :role :structural})}
          nested (root-with [{:type "lv_obj"
                              :uid 9
                              :coords [0 0 99 99]
                              :children [{:type "lv_button"
                                          :uid 1
                                          :coords [10 10 59 39]
                                          :children []}]}])
          fs (judge nested scrollable 0)]
      (is (empty? fs)))))

(deftest nesting-exclusion-does-not-silence-real-siblings
  (testing "CONTROL: the SAME interactive-container table still fires on two
            genuinely independent controls — the exclusion keys on ancestry,
            not on interactivity"
    (let [scrollable {:types (assoc (:types table)
                                    "lv_obj"
                                    {:interactive? true :role :structural})}
          fs (judge (root-with
                     [{:type "lv_obj"
                       :uid 9
                       :coords [0 0 99 99]
                       :children [{:type "lv_button"
                                   :uid 1
                                   :coords [10 10 59 39]
                                   :children []}
                                  {:type "lv_switch"
                                   :uid 2
                                   :coords [40 10 89 39]
                                   :children []}]}])
                    scrollable
                    0)]
      (is (contains? (invariants-of fs) :overlap))
      (is (= "lv_button#1 vs lv_switch#2"
             (:node (first (filter #(= :overlap (:invariant %)) fs))))))))

(deftest hidden-controls-cannot-collide
  (let [fs (judge (root-with
                   [{:type "lv_button"
                     :uid 1
                     :coords [10 10 59 39]
                     :hidden true
                     :children []}
                    {:type "lv_button" :uid 2 :coords [40 10 89 39] :children []}
                    {:type "lv_switch" :uid 3 :coords [45 10 95 39] :children []}]))]
    (testing "lv_indev_search_obj returns NULL for a HIDDEN object and its
              whole subtree (lv_indev.c:623), so it can neither take the
              pointer nor deny it — no pair involving #1 fires"
      (is (not-any? #(re-find #"#1" (str (:node %))) fs)))
    (testing "CONTROL: the two VISIBLE controls with the same kind of overlap
              still fire — the exclusion keys on hidden, not on the geometry"
      (is (= #{"lv_button#2 vs lv_switch#3"} (set (map :node fs)))))))

(deftest a-hidden-ancestor-covers-its-subtree
  (testing "hidden is inherited: a control under a hidden ancestor is just
            as unreachable as the ancestor"
    (let [fs (judge (root-with
                     [{:type "lv_obj"
                       :uid 9
                       :coords [0 0 99 99]
                       :hidden true
                       :children [{:type "lv_button"
                                   :uid 1
                                   :coords [10 10 59 39]
                                   :children []}]}
                      {:type "lv_button" :uid 2 :coords [40 10 89 39] :children []}]))]
      (is (empty? fs)))))

;; ── what does NOT participate ────────────────────────────────────────────

(deftest non-interactive-overlap-is-composition
  (testing "a label stacked on chrome is layout, not an input hazard — the
            rule keys on the :interactive? axis, not on geometry alone"
    (is (empty? (judge (root-with
                        [{:type "lv_label" :uid 1 :coords [10 10 59 39] :children []}
                         {:type "lv_label"
                          :uid 2
                          :coords [10 10 59 39]
                          :children []}]))))))

;; ── the threshold is DATA ────────────────────────────────────────────────

(def ^:private touching-controls
  "Adjacent buttons: #1 ends at x=59, #2 starts at x=60 — zero clear pixels
   between them, but not one shared pixel."
  (root-with [{:type "lv_button" :uid 1 :coords [10 10 59 39] :children []}
              {:type "lv_button" :uid 2 :coords [60 10 109 39] :children []}]))

(deftest touching-passes-at-the-strict-overlap-default
  (testing "gap-px 0 is the strict-overlap rule: sharing a pixel fires,
            merely abutting does not"
    (is (empty? (judge touching-controls table 0)))))

(deftest touching-fires-once-the-threshold-demands-clearance
  (testing "the SAME geometry judged at gap-px 1 fires — raising strictness
            is a data change, never a code change"
    (let [fs (judge touching-controls table 1)]
      (is (= #{:overlap} (invariants-of fs)))
      (is (re-find #"sit 0px apart, under the 1px minimum" (:detail (first fs)))))))

;; ── an unjudged node is a FINDING, never a silent pass ───────────────────

(deftest an-unclassified-type-is-reported-not-skipped
  (let [fs (judge (root-with
                   [{:type "lv_mystery" :uid 1 :coords [10 10 59 39] :children []}
                    {:type "lv_button" :uid 2 :coords [40 10 89 39] :children []}]))]
    (testing "the rule cannot know whether an undeclared type takes the
              pointer, so it says so — passing silently would make 'clean'
              and 'I could not look' the same empty vector"
      (is (contains? (invariants-of fs) :unclassified-type))
      (is (= "lv_mystery"
             (:node (first (filter #(= :unclassified-type (:invariant %)) fs))))))
    (testing "and the unjudged node is NOT paired — the rule neither invents
              a hazard nor hides one"
      (is (not-any? #(= :overlap (:invariant %)) fs)))))

(deftest one-finding-per-type-not-per-node
  (testing "40 unclassified labels are ONE missing table row; 40 copies would
            bury every other finding"
    (let [fs (judge (root-with
                     (for [i (range 5)]
                       {:type "lv_mystery"
                        :uid i
                        :coords [(* i 40) 10 (+ (* i 40) 20) 39]
                        :children []})))]
      (is (= 1 (count (filter #(= :unclassified-type (:invariant %)) fs)))))))

;; ── the producer wires into the registry ─────────────────────────────────

(deftest producer-runs-through-the-registry
  (testing "the rule reaches the gate the way a consumer arms it: appended to
            the producer vector, with :classes supplied"
    (let [res (findings/card-findings
               {:card-id "c"
                :tree overlapping-controls
                :emissions {}
                :host-proxy? false
                :caps {:vis-px? true}
                :classes table
                :producers (conj findings/builtin-producers overlap/producer)})]
      (is (= #{:overlap} (invariants-of (:live res)))))))

(deftest producer-refuses-to-run-without-a-table
  (testing "an omitted :classes throws rather than yielding an empty lane —
            a rule that returns [] for an input it never received is
            indistinguishable from one that found nothing"
    (is (thrown? Exception
                 (findings/card-findings
                  {:card-id "c"
                   :tree overlapping-controls
                   :emissions {}
                   :host-proxy? false
                   :caps {:vis-px? true}
                   :producers (conj findings/builtin-producers overlap/producer)})))))
