(ns devcards.gates-test
  "Canaries for the GOLDEN-DRIFT lane (`devcards.gates/golden-drift-findings`)
   — the lane that makes a devcards run able to fail on PIXELS.

   Before it existed the run re-minted the manifests and compared them to
   nothing: corrupting a committed sha256 exited 0 and silently overwrote the
   corruption back, and a real theme-colour shift moved 243 of 468 hashes with
   `check-renderer` still printing GREEN. So these canaries are pinning the
   presence of a comparison, and the way that comparison FAILED before was by
   returning an empty vector — which is exactly what a decorative canary
   cannot tell from a clean corpus. Every empty-result assertion below is
   therefore PAIRED with a control that must be non-empty for the same input
   class (TODO §7.2), and every clause names the production expression whose
   reversion reds it.

   `devcards.gates` requires only clojure.string + devcards.corpus, both pure,
   so this loads under the :test alias — unlike `devcards.core`, which drags
   the generated bindings and is why the label→map PAIRING lives there behind
   the throws these tests pin."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.gates :as gates]))

(defn- m
  "A manifest `:cards` map in devcards.golden's shape."
  [& id+sha]
  (into {} (map (fn [[id sha]] [id {:sha256 sha :w 800 :h 480}]))
        (partition 2 id+sha)))

(deftest golden-drift-names-all-three-classes-and-its-manifest
  ;; REVERT-TO-BREAK: gates/golden-drift-findings — drop any of the three
  ;; `into` arms (or return [] instead of reducing over corpus/diff-cards) and
  ;; this reds naming the arm that went missing.
  (let [fs (gates/golden-drift-findings
            [{:label :atomic-dark
              :committed (m "clean" "aa" "moved" "bb" "gone" "cc")
              :fresh (m "clean" "aa" "moved" "DRIFTED" "brand-new" "dd")}])
        by-card (into {} (map (juxt :card identity)) fs)]
    (testing "exactly the three drifted cards are found — the CLEAN card is the
              control that keeps this from passing on a lane that flags
              everything"
      (is (= #{"moved" "gone" "brand-new"} (set (keys by-card))))
      (is (= 3 (count fs))))
    (testing "every finding is tagged :golden and carries the manifest it came
              from — four manifests share this lane, so a finding that cannot
              name its own is a finding nobody can act on"
      (is (= #{:golden} (set (map :gate fs))))
      (is (= #{:atomic-dark} (set (map :manifest fs)))))
    (testing "the mismatch detail carries BOTH hashes"
      (is (re-find #"bb -> DRIFTED" (:detail (by-card "moved")))))
    (testing "missing and unexpected are DIFFERENT findings, not one 'differs'"
      (is (re-find #"NOT rendered" (:detail (by-card "gone"))))
      (is (re-find #"ABSENT from the committed" (:detail (by-card "brand-new")))))))

(deftest golden-drift-is-empty-only-because-every-hash-matched
  ;; REVERT-TO-BREAK: gates/golden-drift-findings — the CONTROL half below is
  ;; the one that reds if the lane is reverted to the pre-fix behaviour (mint,
  ;; compare nothing, return []). The clean half alone would stay green.
  (let [cards (m "a" "aa" "b" "bb")
        clean (gates/golden-drift-findings [{:label :atomic-dark
                                             :committed cards
                                             :fresh cards}])
        control (gates/golden-drift-findings [{:label :atomic-dark
                                               :committed cards
                                               :fresh (m "a" "aa" "b" "FLIPPED")}])]
    (is (= [] clean))
    (testing "CONTROL: one flipped hash over the SAME input class must be
              non-empty — an empty result that cannot be made non-empty is a
              lane that never looked"
      (is (= 1 (count control)))
      (is (= "b" (:card (first control)))))))

(deftest golden-drift-judges-every-manifest-it-is-handed
  ;; REVERT-TO-BREAK: the `reduce` over `manifests` — collapse it to judging
  ;; only the first entry and this reds, because the drift is in the LAST one.
  ;; Without it, three of protogen's four committed manifests could rot green.
  (let [clean (m "a" "aa")
        fs (gates/golden-drift-findings
            [{:label :atomic-dark :committed clean :fresh clean}
             {:label :atomic-light :committed clean :fresh clean}
             {:label :composition-dark :committed clean :fresh clean}
             {:label :composition-light :committed clean :fresh (m "a" "MOVED")}])]
    (is (= 1 (count fs)))
    (is (= :composition-light (:manifest (first fs))))))

(deftest golden-drift-refuses-every-shape-of-vacuity
  ;; REVERT-TO-BREAK: the three `(when (empty? …) (throw …))` guards. Each is a
  ;; distinct way to report "clean" having compared nothing; drop any one and
  ;; the matching clause below reds.
  (testing "zero manifests — the whole lane, not run"
    (is (thrown-with-msg? Exception #"ZERO manifests"
                          (gates/golden-drift-findings []))))
  (testing "an empty COMMITTED side — a truncated manifest verifies vacuously"
    (is (thrown-with-msg? Exception #"EMPTY committed golden manifest"
                          (gates/golden-drift-findings
                           [{:label :atomic-dark :committed {} :fresh (m "a" "aa")}]))))
  (testing "an empty FRESH side — this is also what a MIS-PAIRED call site in
            devcards.core looks like (a label paired with a nil map), and it
            must name the label rather than pass"
    (is (thrown-with-msg? Exception #"ZERO fresh renders"
                          (gates/golden-drift-findings
                           [{:label :composition-light
                             :committed (m "a" "aa")
                             :fresh nil}]))))
  (testing "CONTROL: the same call with both sides populated does NOT throw —
            without this, the three clauses above would still pass if the fn
            threw unconditionally"
    (is (= [] (gates/golden-drift-findings
               [{:label :atomic-dark
                 :committed (m "a" "aa")
                 :fresh (m "a" "aa")}])))))
