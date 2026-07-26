(ns devcards.lanes-test
  "Canaries for `devcards.lanes` — the lanes protogen's own gate runs, as
   opposed to the registry machinery under them.

   Two things have to be true here, and only the first is obvious.

   THE ROUTE MUST BE PROVEN LIVE. The corpus yields ZERO findings, so a lane
   that returned [] for every card would produce byte-identical goldens, an
   identical gallery and the same `findings: 0` line. A green battery cannot
   tell a live lane from a dead one, so every canary here feeds a tree that
   MUST produce a finding.

   AND THE THING TESTED MUST BE THE THING THAT RUNS. An earlier cut asserted
   against a registry helper and a producer in isolation, on the theory that
   they stood for the call sites. A review reverted the production
   expressions to their defective form and the whole suite stayed green.
   So the canaries below call `lanes/atomic-findings` and
   `lanes/composition-findings` — the exact fns `devcards.core` calls, with
   the arguments it passes — and the producer-selection test asserts through
   the lane rather than about the helper."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.findings :as findings]
            [devcards.lanes :as lanes]))

(def ^:private clean-tree
  {:type "lv_obj" :coords [0 0 99 99] :children []})

(def ^:private defective-tree
  "A node the renderer flagged as clipped — one of `invariants/defect-flags`."
  {:type "lv_obj" :coords [0 0 99 99]
   :children [{:type "lv_label" :coords [0 0 49 9] :clipped true :children []}]})

(defn- judge
  "Run the DOM lane through the registry exactly as the gate does."
  [expect tree]
  (:live (findings/card-findings {:card-id "c"
                                  :tree tree
                                  :caps {:vis-px? true}
                                  :expect expect
                                  :producers [lanes/tree-producer]})))

(defn- invariants-of [fs] (set (map :invariant fs)))

;; ── the judged arm must actually judge ───────────────────────────────────

(deftest the-judged-arm-reports-a-real-defect-flag
  (testing "the ordinary lane has to FIRE on a defective tree. If it did
            not, every other assertion here would pass against a route that
            judges nothing — which is the exact hole this namespace exists
            to close."
    (is (contains? (invariants-of (judge :judged defective-tree)) :clipped)))
  (testing "CONTROL: a clean tree through the same arm is silent, so the
            assertion keys on the flag and not on the arm being reached"
    (is (empty? (judge :judged clean-tree)))))

;; ── the PRODUCTION entry points, not stand-ins for them ─────────────────
;; These call the same fns `devcards.core` calls, with the same arguments.
;; The previous canaries here pinned things that RESEMBLED the call sites —
;; the registry helper, the producer in isolation — and a review found both
;; green with the production expression reverted to its defective form.

(deftest a-nil-expect-reaches-the-judged-arm-through-the-REAL-entry-point
  (testing "kitchen sinks carry no :expect at all. Passing nil straight to
            the registry does NOT fall through to a lenient lane — the
            registry treats a nil value as ABSENT and REFUSES the call. So
            the default has to be applied before the call, and if it were
            dropped the kitchen sinks would not be judged leniently, they
            would blow up."
    (is (thrown? Exception
                 (findings/card-findings {:card-id "c"
                                          :tree defective-tree
                                          :caps {:vis-px? true}
                                          :expect nil
                                          :producers [lanes/tree-producer]}))))
  (testing "and through the entry point core.clj actually calls, a nil
            :expect is judged — the defect flag is reported"
    (is (contains? (invariants-of (lanes/atomic-findings "c" nil defective-tree))
                   :clipped)))
  (testing "CONTROL: the same entry point is silent on a clean tree, so the
            assertion above keys on the flag and not on the call succeeding"
    (is (empty? (lanes/atomic-findings "c" nil clean-tree))))
  (testing "and a DECLARED expect still routes — nil is a default, not an
            override that swallows the card's own declaration"
    (is (empty? (lanes/atomic-findings "c" :probe-defect defective-tree)))
    (is (= #{:probe-defect-absent}
           (invariants-of (lanes/atomic-findings "c" :probe-defect clean-tree))))))

(deftest the-composition-lane-runs-BOTH-producers-over-BOTH-modes
  (testing "the entry point core.clj calls must judge the DOM and every
            mode's emissions. If it dropped either producer the corpus would
            still render identically, so only a direct call can tell."
    (let [live (lanes/composition-findings
                "c" defective-tree
                {:dark {:commands [] :reports [] :events []}
                 :light {:commands [{:id "c1"}] :reports [] :events []}})]
      (testing "the DOM producer fired"
        (is (contains? (invariants-of live) :clipped)))
      (testing "the by-mode emission producer fired, and named WHICH mode"
        (is (contains? (invariants-of live) :unexpected-emission))
        (is (= :light (:mode (first (filter #(= :unexpected-emission (:invariant %))
                                            live))))))))
  (testing "CONTROL: a clean tree with nothing captured in either mode is
            silent, so the assertions above key on the inputs and not on the
            producers merely being present"
    (is (empty? (lanes/composition-findings
                 "c" clean-tree
                 {:dark {:commands [] :reports [] :events []}
                  :light {:commands [] :reports [] :events []}})))))

;; ── the inverted arm: absence of the defect is the finding ───────────────

(deftest probe-defect-INVERTS-the-verdict
  (testing "a :probe-defect cell exists to EXHIBIT a defect flag, so a tree
            carrying one is correct and passes"
    (is (empty? (judge :probe-defect defective-tree))))
  (testing "and a clean tree is the FAILURE for that cell — the inversion is
            the whole reason this cannot be the plain tree lane"
    (is (= #{:probe-defect-absent} (invariants-of (judge :probe-defect clean-tree))))))

(deftest probe-pixel-only-judges-nothing-and-says-so
  (testing "a pixel-only probe is exempt from the DOM lane by declaration.
            The arm must be selected by :expect, not reached by accident —
            so it stays silent on a tree the judged arm reports."
    (is (empty? (judge :probe-pixel-only defective-tree)))
    (is (contains? (invariants-of (judge :judged defective-tree)) :clipped))))

;; ── producer selection is by NAME ────────────────────────────────────────

(deftest a-builtin-producer-is-selected-by-id-not-position
  (testing "the helper resolves by name and throws on an unknown id rather
            than resolving to whatever sits at that index"
    (is (= :tree (:id (findings/builtin-producer :tree))))
    (is (= :emission (:id (findings/builtin-producer :emission))))
    (is (thrown? Exception (findings/builtin-producer :no-such-lane))))
  (testing "and the COMPOSITION LANE is pinned to the rule it means, not to
            a vector index. This is the assertion that catches the actual
            regression: `(first builtin-producers)` resolves to :tree today,
            so a test of the helper alone stays green while the production
            call site has been reverted. Growing the builtin vector at the
            front must not silently repoint the lane."
    (with-redefs [findings/builtin-producers
                  (into [{:id :decoy :fn (fn [_] []) :requires #{}}]
                        findings/builtin-producers)]
      (is (= :tree (:id (findings/builtin-producer :tree))))
      (testing "the lane still judges the DOM with the decoy in front —
                a positional selection would pick the decoy and go silent"
        (is (contains? (invariants-of
                        (lanes/composition-findings
                         "c" defective-tree
                         {:dark {:commands [] :reports [] :events []}}))
                       :clipped))))))
