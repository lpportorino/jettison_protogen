(ns scratchcard.lanes-test
  "Pins that this tool judges with THIS REPO'S lanes, not its own.

  The identity assertions are the point. If `producers` or `thresholds` ever
  stop being the values `devcards.lanes` exports, this tool forks the standard
  silently: two surfaces required to agree would start disagreeing about
  whether the same screen is defective, and nothing else in the tree would
  notice.

  Fixtures here are hand-built dump maps, which pins the WIRING and the
  author's model of it — not the renderer. Judging real renders is the Tier-2
  lane's job, and these fixtures are deliberately shaped so the vocabulary
  hazards (an absent key meaning a DEFAULT rather than 'no information') do
  not bite: `clickable` absent means CLICKABLE, `disabled` absent means
  enabled."
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [devcards.lanes :as dlanes]
   [scratchcard.lanes :as lanes]))

(defn- root
  [& children]
  {:type "lv_obj" :coords [0 0 799 479] :children (vec children)})

(defn- node
  "A dump node. Named `cls` rather than `type` — binding `type` would shadow
  `clojure.core/type`, the rename hazard this repo has been bitten by twice."
  [cls coords & {:as extra}]
  (merge {:type cls :coords coords :children []} extra))

(defn- judge-live
  [tree]
  (:live (lanes/judge {:card-id "probe" :tree tree :emissions {} :family 0})))

(defn- invariants
  [tree]
  (set (map :invariant (judge-live tree))))

(deftest the-armed-set-is-read-from-the-gate-not-restated
  (testing "every producer the corpus gate arms is armed here"
    (let [ours (set (map :id lanes/producers))]
      (doseq [p dlanes/atomic-producers]
        (is (contains? ours (:id p))
            (str (:id p) " is armed in devcards.lanes but not here")))))
  (testing "the ONE addition is the emission builtin, and it is declared"
    (is (= #{:emission}
           (set/difference (set (map :id lanes/producers))
                           (set (map :id dlanes/atomic-producers))))))
  (testing "thresholds and classes are the gate's own values, not copies"
    (is (identical? dlanes/producer-thresholds lanes/thresholds))
    (is (identical? dlanes/overlap-classes lanes/classes)))
  (testing "caps declares vis-px — omitting it deletes :zero-visible-area entirely"
    (is (= {:vis-px? true} lanes/caps))))

(deftest overlap-fires-on-a-planted-pair-and-not-on-a-control
  ;; Two independently-placed clickable siblings sharing a pixel. Invisible to
  ;; every pixel oracle: the framebuffer is identical whether the one
  ;; underneath was reachable or dead.
  (is (contains? (invariants (root (node "lv_button" [10 10 110 60])
                                   (node "lv_button" [50 30 150 80])))
                 :overlap))
  (testing "CONTROL: the same two buttons, separated, report nothing"
    (is (empty? (invariants (root (node "lv_button" [10 10 110 60])
                                  (node "lv_button" [200 200 300 250]))))))
  (testing "touching but not overlapping is clean at gap-px 0"
    ;; coords are INCLUSIVE, so x2=110 and x1=111 are adjacent, not shared.
    (is (empty? (invariants (root (node "lv_button" [10 10 110 60])
                                  (node "lv_button" [111 10 200 60])))))))

(deftest the-disabled-dead-zone-is-armed
  ;; LV_STATE_DISABLED does NOT remove a widget from the pointer path: a
  ;; disabled control painted over an enabled one absorbs the press and drops
  ;; it. Neither a pixel oracle nor an event log can see that.
  ;; lv_indev_search_obj walks children in REVERSE, so the LATER sibling wins.
  (let [found (invariants (root (node "lv_button" [10 10 110 60])
                                (node "lv_button" [50 30 150 80] :disabled true)))]
    (is (contains? found :disabled-dead-zone)
        "a disabled sibling winning the hit test over an enabled one must fire")))

(deftest an-unjudgeable-node-is-a-finding-not-a-skip
  ;; "clean" and "I could not look" must never be the same empty vector.
  (let [found (invariants (root (node "lv_totally_made_up" [10 10 110 60])))]
    (is (contains? found :unclassified-type))))

(deftest a-truncated-dump-is-reported-rather-than-judged
  ;; The renderer signals dump-buffer overflow with a positive key; a rule that
  ;; parsed on regardless would judge a partial tree and report clean.
  (is (contains? (invariants {:truncated true :children []}) :dump-truncated)))

(deftest emissions-are-judged-because-this-lane-supplies-them
  (testing "an unexpected host command is caught"
    (let [live (:live (lanes/judge {:card-id "probe" :tree (root)
                                    :emissions {:commands [{:id 1}] :reports []
                                                :events [] :proxy-reports []}
                                    :family 0}))]
      (is (contains? (set (map :invariant live)) :unexpected-emission))))
  (testing "CONTROL: no emissions, no finding"
    (is (empty? (:live (lanes/judge {:card-id "probe" :tree (root)
                                     :emissions {:commands [] :reports []
                                                 :events [] :proxy-reports []}
                                     :family 0}))))))

(deftest family-is-required-and-carried
  ;; A family defaulted to 0 would judge every render as though it were the
  ;; shipped theme — the exact blindness that hides a theme-specific defect.
  (doseq [family [0 1 2]]
    (let [live (:live (lanes/judge {:card-id "probe" :family family :emissions {}
                                    :tree (root (node "lv_button" [10 10 110 60])
                                                (node "lv_button" [50 30 150 80]))}))]
      (is (= #{family} (set (map :family live)))
          (str "findings from family " family " must carry it")))))

(deftest summarise-separates-could-not-look-from-found-something
  (let [unjudgeable (judge-live (root (node "lv_totally_made_up" [10 10 110 60])))
        real (judge-live (root (node "lv_button" [10 10 110 60])
                               (node "lv_button" [50 30 150 80])))
        s (lanes/summarise [{:cell "a" :live unjudgeable :stale-exemptions []}
                            {:cell "b" :live real :stale-exemptions []}])]
    (is (= (+ (count unjudgeable) (count real)) (:count s)))
    (is (pos? (:unjudged s)) "an unclassified type must count as COULD-NOT-JUDGE")
    (is (false? (:clean? s)))
    (testing "per-cell attribution is kept, so a reader knows WHICH render"
      (is (contains? (:by-cell s) "a"))
      (is (contains? (:by-cell s) "b")))
    (testing "and the one-line description says both things"
      (is (re-find #"COULD-NOT-JUDGE" (lanes/describe s))))))

(deftest a-clean-run-summarises-cleanly
  (let [s (lanes/summarise [{:cell "a" :live [] :stale-exemptions []}])]
    (is (true? (:clean? s)))
    (is (zero? (:count s)))
    (is (zero? (:unjudged s)))
    (is (= "lanes: clean" (lanes/describe s)))))

(deftest the-descriptor-carries-what-a-diff-needs
  (let [d (lanes/descriptor)]
    (is (= (set (map :id lanes/producers)) (set (:producers d))))
    (is (= lanes/thresholds (:thresholds d)))
    (is (= lanes/caps (:caps d)))
    (is (re-matches #"[0-9a-f]{64}" (:classes-sha d)))
    (testing "declined producers are NAMED, so silence is never read as coverage"
      (is (= [:layers :palette :border] (:declined d))))))
