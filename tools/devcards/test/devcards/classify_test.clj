(ns devcards.classify-test
  "Shape and totality tests for the consumer classification table
   (`devcards.classify`).

   The table is the seam where a consumer teaches this runner about a
   widget it has never seen. Its whole value is that an element the table
   does NOT cover comes out as a finding rather than as silence, so the
   totality tests below are the point of the ns, not an edge case."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.classify :as classify]
            [devcards.invariants :as invariants]))

(def ^:private table
  {:types {"lv_button" {:interactive? true :role :interactive}
           "lv_label" {:interactive? false :role :text}}})

;; ── shape ────────────────────────────────────────────────────────────────

(deftest a-well-formed-table-validates
  (is (= table (classify/validate-table! table))))

(deftest both-axes-are-mandatory
  (testing ":interactive? drives overlap and :role drives the per-role
            thresholds — neither can stand in for the other, so a table
            declaring only one is incomplete rather than partially useful"
    (is (thrown? Exception
                 (classify/validate-table! {:types {"lv_button" {:role :interactive}}})))
    (is (thrown? Exception
                 (classify/validate-table! {:types {"lv_button" {:interactive? true}}})))))

(deftest the-role-set-is-closed
  (testing "an unknown role would key into no threshold arm at all"
    (is (thrown? Exception
                 (classify/validate-table!
                  {:types {"lv_button" {:interactive? true :role :widget}}})))))

(deftest a-contradictory-entry-is-rejected
  (testing ":role :interactive IS the claim that it takes the pointer, so
            pairing it with :interactive? false is a typo, not a nuance"
    (is (thrown? Exception
                 (classify/validate-table!
                  {:types {"lv_button" {:interactive? false :role :interactive}}})))))

(deftest a-structural-element-may-still-be-interactive
  (testing "a scrollable container is genuinely both — this is why the two
            axes are separate fields"
    (is (classify/validate-table!
         {:types {"lv_obj" {:interactive? true :role :structural}}}))))

(deftest unknown-keys-are-rejected
  (is (thrown? Exception
               (classify/validate-table!
                {:types {"lv_button" {:interactive? true :role :interactive :z 3}}})))
  (is (thrown? Exception (classify/validate-table! {:types {} :roles #{}}))))

;; ── lookup refuses to guess ──────────────────────────────────────────────

(deftest an-undeclared-type-classifies-as-nil
  (testing "nil rather than a fallback, so every caller must decide visibly
            what an unknown type means to it"
    (is (nil? (classify/classify table "lv_mystery")))
    (is (= {:interactive? true :role :interactive} (classify/classify table "lv_button")))))

(deftest an-explicit-default-is-honoured
  (testing "opting out of totality is allowed, but it must be a full
            classification written down in the consumer's config — a
            visible, greppable decision rather than a silent fallthrough"
    (let [with-default (assoc table :default {:interactive? false :role :structural})]
      (is (= {:interactive? false :role :structural}
             (classify/classify with-default "lv_mystery"))))))

(deftest a-malformed-default-is-rejected
  (is (thrown? Exception
               (classify/validate-table! (assoc table :default {:role :structural})))))

;; ── totality is reported, not assumed ────────────────────────────────────

(def ^:private mixed-tree
  {:type "lv_obj"
   :coords [0 0 99 99]
   :children [{:type "lv_button" :uid 1 :coords [0 0 9 9] :children []}
              {:type "lv_mystery" :uid 2 :coords [0 0 9 9] :children []}
              {:type "lv_mystery" :uid 3 :coords [20 0 29 9] :children []}
              {:type "lv_enigma" :uid 4 :coords [40 0 49 9] :children []}]})

(deftest undeclared-types-become-findings
  (let [nodes (invariants/annotate-tree mixed-tree)
        fs (classify/unclassified-findings "c" table nodes :overlap)]
    (testing "one finding per DISTINCT type — the root lv_obj and both
              lv_mystery nodes are three nodes but two missing table rows"
      (is (= #{"lv_obj" "lv_mystery" "lv_enigma"} (set (map :node fs))))
      (is (= 3 (count fs))))
    (testing "every finding carries the rule that could not proceed, so the
              message says what went UNJUDGED rather than merely unnamed"
      (is (every? #(= :unclassified-type (:invariant %)) fs))
      (is (every? #(re-find #"overlap could not judge" (:detail %)) fs)))
    (testing "findings are ordered, so a corpus diff is stable"
      (is (= ["lv_button" "lv_enigma" "lv_mystery" "lv_obj"]
             (sort (conj (mapv :node fs) "lv_button")))))))

(deftest a-fully-declared-tree-yields-nothing
  (testing "CONTROL: the same walk against a table that covers every type is
            silent — so the assertions above are keyed on the missing rows,
            not on the lane always emitting"
    (let [full (assoc-in table [:types "lv_obj"] {:interactive? false :role :structural})
          tree {:type "lv_obj"
                :coords [0 0 99 99]
                :children [{:type "lv_button" :uid 1 :coords [0 0 9 9] :children []}]}]
      (is (empty? (classify/unclassified-findings
                   "c" full (invariants/annotate-tree tree) :overlap))))))

(deftest a-default-satisfies-totality
  (testing "with an explicit :default nothing is unclassified — the consumer
            has said, on the record, what unknown types mean"
    (let [with-default (assoc table :default {:interactive? false :role :structural})]
      (is (empty? (classify/unclassified-findings
                   "c" with-default (invariants/annotate-tree mixed-tree) :overlap))))))

(deftest a-FALSEY-default-is-rejected
  (testing "`:default false` once slipped past a when-let binding unvalidated,
            and then switched totality enforcement off wholesale: classify
            returned false, (some? false) is true, so every undeclared type
            read as declared and the whole :unclassified-type class went
            silent — output byte-identical to a clean run. One word, in the one
            namespace whose stated purpose is that an unjudged element is never
            silence."
    (is (thrown? Exception (classify/validate-table! (assoc table :default false)))))
  (testing "CONTROL: an ABSENT default is still fine — that is how a consumer
            spells 'enforce totality', and rejecting it would be the opposite
            error"
    (is (classify/validate-table! (dissoc table :default)))
    (is (nil? (classify/classify table "lv_mystery")))))

(deftest a-falsey-default-cannot-silence-the-finding-lane
  (testing "the end-to-end consequence, not just the validator: with the hole
            open this returned [] where the no-default table returns findings"
    (let [nodes (invariants/annotate-tree mixed-tree)]
      (is (seq (classify/unclassified-findings "c" table nodes :overlap)))
      (is (thrown? Exception
                   (classify/unclassified-findings
                    "c" (classify/validate-table! (assoc table :default false))
                    nodes :overlap))))))
