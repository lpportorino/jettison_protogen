(ns devcards.expect-test
  "Canaries for `devcards.expect` — the DOM lane protogen's own gate runs,
   as opposed to the registry machinery under it.

   This namespace exists because the route had no test of any kind, and the
   only evidence offered for it was that a re-render moved nothing. That
   proof is vacuous here: the corpus yields ZERO findings, so a route
   returning [] for every card produces byte-identical goldens, an
   identical gallery and the same `findings: 0` line. A green battery
   cannot tell the live route from a dead one, so the route needs trees
   that MUST produce findings."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.expect :as expect]
            [devcards.findings :as findings]))

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
                                  :producers [expect/tree-producer]})))

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

(deftest a-nil-expect-is-judged-not-refused
  (testing "kitchen sinks carry no :expect. The registry treats a nil value
            as ABSENT, so the gate substitutes :judged — a card that fell
            through to 'no expect, no judgement' would be silently exempt."
    (is (contains? (invariants-of (judge :judged defective-tree)) :clipped))))

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
  (testing "the composition lane needs the :tree builtin. Selecting it as
            `(first builtin-producers)` couples the lane to a vector order
            nothing declares — append or reorder an entry and the lane
            silently judges with a different rule."
    (is (= :tree (:id (findings/builtin-producer :tree))))
    (is (= :emission (:id (findings/builtin-producer :emission)))))
  (testing "an id that is not there THROWS rather than resolving to whatever
            sits at that index"
    (is (thrown? Exception (findings/builtin-producer :no-such-lane)))))
