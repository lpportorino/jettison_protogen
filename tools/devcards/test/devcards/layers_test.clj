(ns devcards.layers-test
  "Canaries for the layer contract (`devcards.layers`).

   The POC this contract comes from went red for the WRONG REASON on its
   first run: its findings were same-layer overlap between a button and its
   own label — legitimate nesting — while the z-inversion clause never
   fired at all. Stopping at 'it goes red' would have reported a validated
   design on evidence that said nothing about z-inversion.

   So every clause here gets its OWN canary, and each is written so that it
   can only pass for its own reason: the assertions check the :invariant
   AND that no other clause produced it. The known-bad topology (chrome
   declared above a video proxy the compositor punches on top) is
   reproduced synthetically — a private consumer's actual screens cannot come here,
   and the corpus is gate-enforced secret-free.

   The real 4-prompt capture going red remains the CONSUMER's proof. These
   canaries prove the clauses fire; they do not prove the consumer's screen
   is broken."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.layers :as layers]
            [devcards.lvgl-classes :as lvgl]
            [devcards.overlap :as overlap]))

(defn- judge
  [root declaration proxy-rects & [gap-px]]
  (layers/findings {:card-id "c"
                    :nodes (invariants/annotate-tree root)
                    :declaration declaration
                    :proxy-rects (or proxy-rects [])
                    :thresholds {:gap-px (or gap-px 0)}}))

(defn- invariants-of [fs] (set (map :invariant fs)))

;; ── the known-bad topology, synthetically ────────────────────────────────
;; A chrome panel declared ABOVE a video surface, where the video surface is
;; a host proxy the compositor punches after LVGL finishes. The widget tree
;; says chrome paints last (it is the later sibling); the compositor says
;; otherwise. Declared by intent, the contract catches it.

(def ^:private chrome-over-video
  {:type "lv_obj"
   :uid 1
   :coords [0 0 799 479]
   :children [{:type "lv_obj" :uid 7 :coords [0 0 799 479] :children []}
              {:type "lv_obj" :uid 12 :coords [0 400 799 479] :children []}]})

(def ^:private declaration
  "Chrome above video, BY INTENT. Note this is the opposite of what the
   compositor actually does — which is the entire point."
  {:layers {12 {:z 10 :id "chrome"}
            7 {:z 0 :id "video"}}})

(deftest the-compositor-punch-is-caught-as-a-z-inversion
  (testing "the proxy is painted after LVGL, so it lands on top of chrome
            that the declaration puts above it — the known-bad case"
    (let [fs (judge chrome-over-video declaration [{:uid 7 :proxy-id "px"
                                                    :coords [0 0 799 479]}])]
      (is (= #{:layer-inversion} (invariants-of fs)))
      (is (= 1 (count fs)))
      (testing "and the message names the compositor, not just the z numbers —
                a reader has to know WHY no widget z-order can fix it"
        (is (re-find #"punches it after LVGL" (:detail (first fs))))
        (is (re-find #"\"video\" \(z 0\) is painted OVER" (:detail (first fs))))))))

(deftest without-the-proxy-rect-the-same-tree-is-CLEAN
  (testing "CONTROL, and the sharpest one here: the identical tree and the
            identical declaration, judged with NO proxy rect, passes —
            because by widget paint order chrome (the later sibling) really
            is on top. The finding above is therefore attributable to the
            compositor input alone, not to the geometry or the z values."
    (is (empty? (judge chrome-over-video declaration [])))))

(deftest deriving-z-from-paint-order-would-have-passed-the-bug
  (testing "the tautology this contract exists to avoid: had z been read off
            what renders, video would have earned the HIGHER z (it is on
            top), and the inverted declaration would agree with reality"
    (let [derived {:layers {12 {:z 0 :id "chrome"} 7 {:z 10 :id "video"}}}]
      (is (empty? (judge chrome-over-video derived
                         [{:uid 7 :proxy-id "px" :coords [0 0 799 479]}])))))
  (testing "so a green run under a DERIVED declaration proves nothing — only
            the intent-declared one above can fail"))

;; ── each remaining clause, on its own ────────────────────────────────────

(deftest same-layer-overlap-is-a-violation
  (let [tree {:type "lv_obj"
              :uid 1
              :coords [0 0 199 199]
              :children [{:type "lv_obj" :uid 2 :coords [10 10 59 39] :children []}
                         {:type "lv_obj" :uid 3 :coords [40 10 89 39] :children []}]}
        fs (judge tree {:layers {}} [])]
    (testing "both undeclared, so both are in the implicit root layer at z 0"
      (is (= #{:layer-same} (invariants-of fs)))
      (is (re-find #"\(undeclared\)" (:detail (first fs)))))))

(deftest an-undeclared-subtree-gets-the-STRICT-rule-not-an-exemption
  (testing "default z 0 means absence of a declaration is never a free pass"
    (is (seq (judge {:type "lv_obj"
                     :uid 1
                     :coords [0 0 199 199]
                     :children [{:type "lv_obj" :uid 2 :coords [0 0 50 50] :children []}
                                {:type "lv_obj" :uid 3 :coords [25 25 75 75] :children []}]}
                    {:layers {}}
                    [])))))

(deftest different-layers-with-equal-z-are-ambiguous
  (testing "which paints on top is unspecified, so the stack is a coin flip
            rather than a design — a violation, not a pass"
    (let [tree {:type "lv_obj"
                :uid 1
                :coords [0 0 199 199]
                :children [{:type "lv_obj" :uid 2 :coords [10 10 59 39] :children []}
                           {:type "lv_obj" :uid 3 :coords [40 10 89 39] :children []}]}
          fs (judge tree {:layers {2 {:z 5 :id "a"} 3 {:z 5 :id "b"}}} [])]
      (is (= #{:layer-ambiguous} (invariants-of fs))))))

(deftest a-correctly-ordered-stack-passes
  (testing "different layers, higher z painted on top — the declared stack,
            which must NOT fire or the contract forbids composition itself"
    (let [tree {:type "lv_obj"
                :uid 1
                :coords [0 0 199 199]
                :children [{:type "lv_obj" :uid 2 :coords [10 10 59 39] :children []}
                           {:type "lv_obj" :uid 3 :coords [40 10 89 39] :children []}]}]
      (testing "uid 3 is the later sibling, so it paints on top; declare it higher"
        (is (empty? (judge tree {:layers {2 {:z 0 :id "back"}
                                          3 {:z 10 :id "front"}}} []))))
      (testing "MIRROR: swap the declaration and the identical geometry fails —
                the verdict tracks the declaration, which is what makes it a
                contract rather than a description"
        (is (= #{:layer-inversion}
               (invariants-of (judge tree {:layers {2 {:z 10 :id "front"}
                                                    3 {:z 0 :id "back"}}} []))))))))

(deftest nesting-is-not-a-layer-violation
  (testing "the POC's first-run defect: a child inside its parent overlaps by
            construction. Ancestry must exclude it, or every run goes red for
            nesting while the clause under test never fires."
    (is (empty? (judge {:type "lv_obj"
                        :uid 1
                        :coords [0 0 199 199]
                        :children [{:type "lv_obj"
                                    :uid 2
                                    :coords [0 0 99 99]
                                    :children [{:type "lv_label"
                                                :uid 3
                                                :coords [10 10 59 39]
                                                :children []}]}]}
                       {:layers {}}
                       [])))))

(def ^:private inherited-only
  "The ONLY judged pair is uid4 vs uid5, and NEITHER declares its own layer —
   each inherits from its declaring parent. Every other pair is either
   ancestor-related (excluded) or non-overlapping, so nothing else can satisfy
   an assertion about this tree. That isolation is the point: the previous
   version of this canary asserted `contains? :layer-inversion` on a tree where
   a DIRECTLY-declared pair also produced one, so deleting subtree inheritance
   outright left it green."
  {:type "lv_obj"
   :uid 1
   :coords [0 0 199 199]
   :children [{:type "lv_obj"
               :uid 2
               :coords [0 0 79 99]
               :children [{:type "lv_obj" :uid 4 :coords [10 10 89 89]
                           :children []}]}
              {:type "lv_obj"
               :uid 3
               :coords [100 0 199 99]
               :children [{:type "lv_obj" :uid 5 :coords [80 10 150 89]
                           :children []}]}]})

(deftest a-layer-declaration-covers-its-whole-subtree
  (testing "nearest DECLARING ancestor: uid4 and uid5 declare nothing, so the
            verdict is produced ENTIRELY by the layers their parents declare.
            uid5 is later in pre-order and therefore on top, while the
            declaration puts uid4's layer above — an inversion visible only
            through inheritance."
    (let [fs (judge inherited-only
                    {:layers {2 {:z 10 :id "front"} 3 {:z 0 :id "back"}}}
                    [])]
      (is (= 1 (count fs)) (str "expected exactly one judged pair, got " (mapv :node fs)))
      (is (= :layer-inversion (:invariant (first fs))))
      (is (= "lv_obj#4 vs lv_obj#5" (:node (first fs)))))))

;; ── loud failure ─────────────────────────────────────────────────────────

(deftest an-unresolvable-proxy-throws
  (testing "the POC's silent-nil defect: a proxy matching no node was dropped
            and counted as ordinary chrome, so the whole z-inversion check
            reported CLEAN — broken output byte-identical to nothing-to-report"
    (is (thrown? Exception
                 (judge chrome-over-video declaration
                        [{:uid 999 :proxy-id "px" :coords [0 0 10 10]}])))))

(deftest hidden-elements-do-not-stack
  (testing "a hidden node draws nothing, so it cannot be painted over anything"
    (is (empty? (judge {:type "lv_obj"
                        :uid 1
                        :coords [0 0 199 199]
                        :children [{:type "lv_obj" :uid 2 :coords [10 10 59 39]
                                    :hidden true :children []}
                                   {:type "lv_obj" :uid 3 :coords [40 10 89 39]
                                    :children []}]}
                       {:layers {}}
                       [])))))

;; ── registry wiring ──────────────────────────────────────────────────────

(deftest the-producer-runs-through-the-registry
  (let [res (findings/card-findings
             {:card-id "c"
              :tree chrome-over-video
              :emissions {}
              :host-proxy? false
              :caps {:vis-px? true}
              :declaration declaration
              :proxy-rects [{:uid 7 :proxy-id "px" :coords [0 0 799 479]}]
              :producers (conj findings/builtin-producers layers/producer)})]
    (is (= #{:layer-inversion} (invariants-of (:live res))))))

(deftest omitting-proxy-rects-throws-rather-than-passing
  (testing "supplied-but-empty is the claim 'no proxy surfaces'; absent is an
            oversight, and the difference decides whether a CLEAN result means
            anything"
    (is (thrown? Exception
                 (findings/card-findings
                  {:card-id "c"
                   :tree chrome-over-video
                   :emissions {}
                   :host-proxy? false
                   :caps {:vis-px? true}
                   :declaration declaration
                   :producers (conj findings/builtin-producers layers/producer)})))))

(deftest the-threshold-is-data
  (testing "touching elements pass at gap-px 0 and fail at 1 — the layer
            contract's 'no two elements touch' clause is the same knob"
    (let [tree {:type "lv_obj"
                :uid 1
                :coords [0 0 199 199]
                :children [{:type "lv_obj" :uid 2 :coords [10 10 59 39] :children []}
                           {:type "lv_obj" :uid 3 :coords [60 10 109 39] :children []}]}]
      (is (empty? (judge tree {:layers {}} [] 0)))
      (is (= #{:layer-same} (invariants-of (judge tree {:layers {}} [] 1)))))))

;; ── regressions from the hostile re-review ───────────────────────────────
;; Every test below was ABSENT when `dc0e586c` claimed "each clause proved by
;; mutation", and each one fails against the code as that commit shipped it.
;; They are the reason that claim did not hold: the suite exercised only
;; equal-depth pairs, where the defect is invisible.

(def ^:private unequal-depth
  "uid4 sits at path [0 0]; uid3 at path [1]. Their boxes overlap, and they
   are NOT related, so the pair is judged. Pre-order paints [0 0] BEFORE
   [1], so uid3 is on top. uid2 and uid3 are placed apart so the only judged
   pair is the unequal-depth one."
  {:type "lv_obj"
   :uid 1
   :coords [0 0 199 199]
   :children [{:type "lv_obj"
               :uid 2
               :coords [0 0 99 99]
               :children [{:type "lv_obj" :uid 4 :coords [90 90 150 150]
                           :children []}]}
              {:type "lv_obj" :uid 3 :coords [100 100 199 199] :children []}]})

(deftest paint-order-is-pre-order-not-vector-compare
  (testing "Clojure's vector `compare` is COUNT-first: (compare [0 0] [1]) is
            1, though [0 0] paints first. The clause used it and called it
            lexicographic, so for every pair whose earlier node is DEEPER than
            its later one — the ordinary shape of nested chrome — the verdict
            was exactly inverted."
    (testing "declaration CONTRADICTS the render: uid4 declared above uid3
              while uid3 actually paints last. Must FIRE."
      (let [fs (judge unequal-depth
                      {:layers {2 {:z 10 :id "front"} 3 {:z 0 :id "back"}}}
                      [])]
        (is (= #{:layer-inversion} (invariants-of fs)))
        (is (= "lv_obj#4 vs lv_obj#3" (:node (first fs))))))
    (testing "MIRROR — declaration MATCHES the render. Must stay silent. The
              old code inverted both directions, so this arm is as
              load-bearing as the one above."
      (is (empty? (judge unequal-depth
                         {:layers {2 {:z 0 :id "back"} 3 {:z 10 :id "front"}}}
                         []))))))

(def ^:private proxy-with-children
  "A host-proxy (uid7) carrying an LVGL child (uid8), overlapping outside
   chrome (uid9)."
  {:type "lv_obj"
   :uid 1
   :coords [0 0 199 199]
   :children [{:type "lv_obj"
               :uid 7
               :coords [0 0 99 99]
               :children [{:type "lv_obj" :uid 8 :coords [10 10 89 89]
                           :children []}]}
              {:type "lv_obj" :uid 9 :coords [50 50 149 149] :children []}]})

(deftest a-proxys-CHILDREN-are-not-compositor-painted
  (testing "the compositor punches video INTO the proxy's rect after LVGL, so
            the proxy's own widget children end up UNDER the video — not on
            top of everything with it. Treating descendants as
            compositor-painted ruled a proxy's children above every widget
            they overlap."
    (let [fs (judge proxy-with-children
                    {:layers {7 {:z 0 :id "video"} 9 {:z 10 :id "chrome"}}}
                    [{:uid 7 :proxy-id "px" :coords [0 0 99 99]}])
          pairs (set (map :node fs))]
      (testing "the SURFACE really does invert against higher-z chrome"
        (is (contains? pairs "lv_obj#7 vs lv_obj#9")))
      (testing "but its CHILD does not — uid8 paints before uid9 in tree
                order and the punch does not lift it"
        (is (not (contains? pairs "lv_obj#8 vs lv_obj#9")))))))

(deftest two-overlapping-proxies-are-unjudgeable-not-quietly-ordered
  (testing "both are punched after LVGL, so their relative order lives in the
            compositor and is not readable from the widget tree. Falling
            through to tree order would use the very ordering this ns calls
            invalid for proxies, and would ratify whichever declaration
            happened to match it."
    (let [tree {:type "lv_obj"
                :uid 1
                :coords [0 0 199 199]
                :children [{:type "lv_obj" :uid 7 :coords [0 0 99 99] :children []}
                           {:type "lv_obj" :uid 8 :coords [50 50 149 149] :children []}]}
          fs (judge tree
                    {:layers {7 {:z 0 :id "a"} 8 {:z 10 :id "b"}}}
                    [{:uid 7 :proxy-id "px" :coords [0 0 99 99]}
                     {:uid 8 :proxy-id "px" :coords [50 50 149 149]}])]
      (is (= #{:layer-proxy-ambiguous} (invariants-of fs)))
      (testing "and it fires REGARDLESS of which way the declaration runs —
                a clause that could be satisfied by reordering z would just be
                tree order wearing a different name"
        (is (= #{:layer-proxy-ambiguous}
               (invariants-of (judge tree
                                     {:layers {7 {:z 10 :id "a"} 8 {:z 0 :id "b"}}}
                                     [{:uid 7 :proxy-id "px" :coords [0 0 99 99]}
                                      {:uid 8 :proxy-id "px" :coords [50 50 149 149]}]))))))))

(deftest an-unresolvable-DECLARATION-uid-throws
  (testing "the proxy side threw for this and the declaration side did not.
            A dropped declaration leaves its subtree at root z 0, so the
            inversion it was meant to catch disappears and the run reports
            CLEAN — the same byte-identical-to-nothing-to-report failure."
    (is (thrown? Exception
                 (judge unequal-depth
                        {:layers {2 {:z 10 :id "front"} 999 {:z 0 :id "ghost"}}}
                        [])))))

(deftest different-layers-merely-TOUCHING-stay-OK-at-any-threshold
  (testing "§1.4 says different-layer touching is OK. Only the SAME-layer
            clause is a proximity rule; the others reason about which element
            is painted OVER the other, and boxes sharing no pixel are not
            painted over each other at all. At gap-px 1 the old code fired
            :layer-inversion and asserted one layer 'is painted OVER' another
            that it did not cover."
    (let [touching {:type "lv_obj"
                    :uid 1
                    :coords [0 0 199 199]
                    :children [{:type "lv_obj" :uid 2 :coords [10 10 59 39] :children []}
                               {:type "lv_obj" :uid 3 :coords [60 10 109 39] :children []}]}
          decl {:layers {2 {:z 10 :id "front"} 3 {:z 0 :id "back"}}}]
      (is (empty? (judge touching decl [] 1)))
      (testing "CONTROL: the SAME-layer clause still is a proximity rule, so
                undeclared touching neighbours fire at gap-px 1"
        (is (= #{:layer-same} (invariants-of (judge touching {:layers {}} [] 1))))))))

(deftest a-visible-node-with-no-coords-is-reported-not-skipped
  (testing "CLAUDE.md makes an unjudged element a FINDING, never a skip —
            the mandate this series shipped, which this lane did not follow"
    (let [fs (judge {:type "lv_obj"
                     :uid 1
                     :coords [0 0 199 199]
                     :children [{:type "lv_obj" :uid 2 :children []}]}
                    {:layers {}}
                    [])]
      (is (= #{:unmeasurable-node} (invariants-of fs)))
      (is (= "lv_obj#2" (:node (first fs)))))))

;; ── the README's wiring recipe must actually RUN ─────────────────────────
;; The recipe shipped once as a call that threw: it predated the stricter
;; contract and omitted :caps and :host-proxy?. A consumer's first contact
;; with this registry is that snippet, so it is executed here rather than
;; trusted. If the contract tightens again, this fails and the README gets
;; fixed with it.

(deftest the-readme-wiring-recipe-runs
  (let [tree {:type "lv_obj"
              :uid 1
              :coords [0 0 199 199]
              :children [{:type "lv_button" :uid 12 :coords [10 10 59 39]
                          :children []}]}
        classes (lvgl/merge-consumer {:types {"fx_dock" {:interactive? true
                                                         :role :interactive}}})
        res (findings/card-findings
             {:card-id "readme"
              :tree tree
              :emissions {}
              :host-proxy? false
              :caps {:vis-px? true}
              :classes classes
              :declaration {:layers {12 {:z 10 :id "chrome"}}}
              :proxy-rects []
              :producers (conj findings/builtin-producers
                               overlap/producer
                               layers/producer)
              :thresholds {:overlap/gap-px 1 :layers/gap-px 0}})]
    (testing "it returns a verdict rather than throwing"
      (is (map? res))
      (is (contains? res :live)))
    (testing "and the card is clean, so the recipe is demonstrated on a
              working call rather than on an exception path"
      (is (empty? (:live res))))))

(def ^:private twins-in-different-layers
  "Two children with BYTE-IDENTICAL node maps (same type, same coords, no uid)
   under two DIFFERENT declaring parents. They are equal as values, so a memo
   keyed on the node map gives the second one the first one's layer. Keyed on
   :path — unique per node — each resolves through its own ancestor."
  {:type "lv_obj"
   :uid 1
   :coords [0 0 199 199]
   :children [{:type "lv_obj"
               :uid 2
               :coords [0 0 99 99]
               :children [{:type "lv_obj" :coords [10 10 89 89] :children []}]}
              {:type "lv_obj"
               :uid 3
               :coords [0 100 99 199]
               :children [{:type "lv_obj" :coords [10 10 89 89] :children []}]}]})

(deftest identical-node-maps-in-different-layers-resolve-separately
  (testing "the memo key must be the node's POSITION, not its value. Keyed on
            the node map, the two twins share a layer and a genuine
            z-inversion is downgraded to :layer-same — a real defect reported
            as the wrong one, which is the failure this contract exists to
            avoid."
    (let [fs (judge twins-in-different-layers
                    {:layers {2 {:z 10 :id "front"} 3 {:z 0 :id "back"}}}
                    [])
          twins (first (filter #(= "lv_obj vs lv_obj" (:node %)) fs))]
      (is (some? twins) "the twin pair must be judged at all")
      (is (= :layer-inversion (:invariant twins))
          "front (z10) is declared above back (z0), but back paints later"))))

(deftest a-declaration-VALUE-must-parse-not-just-its-key
  (testing "check-declaration! validated only the KEYS. A missing or misspelled
            :z left (:z d) nil — and when BOTH sides were nil, the realistic
            typo, they compared EQUAL and a genuine inversion was silently
            relabelled :layer-ambiguous. A declaration is INTENT; intent that
            does not parse is not intent."
    (is (thrown? Exception
                 (judge unequal-depth
                        {:layers {2 {:zz 10 :id "typo"} 3 {:z 0 :id "back"}}}
                        [])))
    (testing "including the both-sides case, which produced no exception at all
              before — just a wrong verdict"
      (is (thrown? Exception
                   (judge unequal-depth
                          {:layers {2 {:id "a"} 3 {:id "b"}}}
                          []))))
    (testing "and a non-map declaration value, which must say SO rather than
              complaining about a nil :z — the numeric-z check would also
              reject it, so only the message distinguishes the two rules and
              only the message tells the author what to fix"
      (is (thrown-with-msg?
           Exception #"must be a map"
           (judge unequal-depth {:layers {2 10 3 {:z 0 :id "b"}}} [])))))
  (testing "CONTROL: well-formed declarations still judge normally"
    (is (= #{:layer-inversion}
           (invariants-of (judge unequal-depth
                                 {:layers {2 {:z 10 :id "front"}
                                           3 {:z 0 :id "back"}}}
                                 []))))))
