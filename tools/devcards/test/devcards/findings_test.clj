(ns devcards.findings-test
  "Contract tests for the finding-producer registry (`devcards.findings`).

   The registry's job is not to run rules — it is to make the ways a rule
   can go WRONG loud. Every refusal below exists because its silent
   alternative produces output identical to a clean run: an empty producer
   set, a rule that never received its input, a threshold typo that
   relaxes the gate it names. Those are the failures a green gate cannot
   distinguish from success, so each one throws."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]))

(def ^:private clean-tree
  {:type "lv_obj" :coords [0 0 99 99] :children []})

(def ^:private defective-tree
  {:type "lv_obj"
   :coords [0 0 99 99]
   :children [{:type "lv_label" :uid 2 :coords [9 9 199 199] :clipped true :children []}]})

(defn- noop-producer
  ([id] (noop-producer id #{}))
  ([id requires] {:id id :fn (fn [_] []) :requires requires}))

;; ── what the builtins DO and DO NOT reproduce ────────────────────────────
;; Scope, stated because the obvious stronger reading is false: this pins the
;; builtins against DIRECT calls to the two lane fns. It does NOT pin them
;; against protogen's own gate. core.clj composes the lanes by hand, routes on
;; :expect, and runs the emission lane TWICE (dark and light) — shapes
;; builtin-producers does not express and this test cannot certify.

(deftest builtin-producers-match-a-direct-call-to-the-two-lanes
  (testing "same inputs, same findings — modulo the :producer tag the registry
            adds so a finding can be traced back to the rule that made it"
    (let [via-registry (findings/card-findings {:card-id "c"
                                                :tree defective-tree
                                                :emissions {}
                                                :host-proxy? false
                                                :caps {:vis-px? true}})
          direct (into (invariants/tree-findings "c" defective-tree {:vis-px? true})
                       (invariants/emission-findings "c" {} false))]
      (is (= direct (mapv #(dissoc % :producer) (:live via-registry))))
      (testing "and every finding names its producer"
        (is (= #{:tree} (set (map :producer (:live via-registry)))))))))

(deftest the-builtins-declare-every-input-they-read
  (testing "an earlier shape defaulted :caps to {:vis-px? false}, which
            SILENTLY deleted the whole :zero-visible-area class — the lane
            already guards on (contains? node :vis_px), so a false default can
            only suppress real findings, never prevent spurious ones. Omitting
            :caps must therefore throw, not quietly weaken the gate."
    (is (thrown? Exception
                 (findings/card-findings {:card-id "c"
                                          :tree defective-tree
                                          :emissions {}
                                          :host-proxy? false})))
    (testing "same for :host-proxy?, whose absence drops the POSITIVE
              'a host_proxy card must emit exactly one proxy-report' contract"
      (is (thrown? Exception
                   (findings/card-findings {:card-id "c"
                                            :tree defective-tree
                                            :emissions {}
                                            :caps {:vis-px? true}}))))))

(deftest the-occlusion-lane-really-is-what-caps-gates
  (testing "the CONTROL that makes the previous test meaningful: with :caps
            supplied the lane fires, so the throw is protecting a real check
            rather than a hypothetical one"
    (let [occluded {:type "lv_obj"
                    :coords [0 0 99 99]
                    :children [{:type "lv_obj" :uid 2 :coords [9 9 49 49]
                                :vis_px 0 :children []}]}]
      (is (contains? (set (map :invariant
                               (:live (findings/card-findings
                                       {:card-id "c" :tree occluded :emissions {}
                                        :host-proxy? false
                                        :caps {:vis-px? true}}))))
                     :zero-visible-area))
      (is (not (contains? (set (map :invariant
                                    (:live (findings/card-findings
                                            {:card-id "c" :tree occluded :emissions {}
                                             :host-proxy? false
                                             :caps {:vis-px? false}}))))
                          :zero-visible-area))))))

(deftest a-nil-valued-key-counts-as-ABSENT
  (testing "nil is how a missing value spells itself, so accepting it would
            reopen the silence through the front door"
    (is (thrown? Exception
                 (findings/card-findings {:card-id "c"
                                          :tree clean-tree
                                          :proxy-rects nil
                                          :producers [(noop-producer :p #{:proxy-rects})]}))))
  (testing "but FALSE is a legitimate claim, not an omission — :host-proxy?
            false is the ordinary case"
    (is (= [] (:live (findings/card-findings
                      {:card-id "c"
                       :tree clean-tree
                       :emissions {}
                       :host-proxy? false
                       :caps {:vis-px? true}}))))))

(deftest a-caller-supplied-nodes-key-is-refused
  (testing ":nodes is registry-DERIVED. Accepting it let a producer requiring
            :nodes run against a walk the registry never made — and with :tree
            absent, against nil, returning [] instead of throwing."
    (is (thrown? Exception
                 (findings/card-findings {:card-id "c"
                                          :nodes []
                                          :producers [(noop-producer :p #{:nodes})]})))))

(deftest a-consumer-producer-composes-with-the-builtins
  (let [extra {:id :extra
               :fn (fn [{:keys [card-id]}]
                     [{:card card-id :invariant :custom :detail "from a consumer"}])
               :requires #{:tree}}
        res (findings/card-findings {:card-id "c"
                                     :tree clean-tree
                                     :emissions {}
                                     :host-proxy? false
                                     :caps {:vis-px? true}
                                     :producers (conj findings/builtin-producers extra)})]
    (testing "the consumer's rule reaches the verdict without patching this repo"
      (is (= [:custom] (mapv :invariant (:live res)))))))

;; ── the context is the contract ──────────────────────────────────────────

(deftest producers-receive-the-shared-annotated-walk
  (testing "ancestry is precomputed ONCE and handed to every producer, so a
            consumer rule never re-walks and never disagrees about what is
            hidden, snapped, or nested"
    (let [seen (atom nil)
          probe {:id :probe
                 :fn (fn [ctx] (reset! seen ctx) [])
                 :requires #{:nodes}}]
      (findings/card-findings {:card-id "c"
                               :tree defective-tree
                               :emissions {}
                               :producers [probe]})
      (is (= 2 (count (:nodes @seen))))
      (is (= [[] [0]] (mapv :path (:nodes @seen))))
      (testing "and the tree, declaration and proxy-rect inputs are all
                reachable from that one map — a (fn [tree] …) producer
                could not express the layer contract at all"
        (is (contains? @seen :tree))
        (is (contains? @seen :nodes))))))

(deftest declaration-and-proxy-rects-pass-through
  (testing "consumer INTENT and compositor rects are first-class context:
            host-proxy stacking happens after LVGL finishes, so it is not
            readable from the widget tree and must arrive as its own input"
    (let [seen (atom nil)
          probe {:id :probe
                 :fn (fn [ctx] (reset! seen ctx) [])
                 :requires #{:declaration :proxy-rects}}]
      (findings/card-findings {:card-id "c"
                               :tree clean-tree
                               :declaration {:layers {"chrome" 10 "video" 0}}
                               :proxy-rects [{:id "px" :coords [0 0 10 10]}]
                               :producers [probe]})
      (is (= {"chrome" 10 "video" 0} (:layers (:declaration @seen))))
      (is (= [{:id "px" :coords [0 0 10 10]}] (:proxy-rects @seen))))))

(deftest a-required-input-that-was-never-supplied-throws
  (testing "a rule that quietly returns [] for an input it never received is
            indistinguishable from a rule that found nothing"
    (is (thrown? Exception
                 (findings/card-findings {:card-id "c"
                                          :tree clean-tree
                                          :producers [(noop-producer :p #{:proxy-rects})]})))))

(deftest supplied-but-empty-is-a-claim-not-an-oversight
  (testing "passing :proxy-rects [] asserts 'this card has no proxy
            surfaces'; omitting the key asserts nothing. The registry must
            tell those apart or the assertion is worthless."
    (is (= [] (:live (findings/card-findings
                      {:card-id "c"
                       :tree clean-tree
                       :proxy-rects []
                       :producers [(noop-producer :p #{:proxy-rects})]}))))))

;; ── thresholds are data, and a typo cannot relax the gate ────────────────

(def ^:private threshold-producer
  {:id :demo
   :fn (fn [{:keys [card-id thresholds]}]
         [{:card card-id :invariant :demo :detail (str "gap=" (:gap-px thresholds))}])
   :requires #{:tree}
   :thresholds {:gap-px {:pred nat-int? :default 3 :doc "clear pixels"}}})

(deftest a-declared-default-applies-when-nothing-is-supplied
  (is (= "gap=3"
         (:detail (first (:live (findings/card-findings
                                 {:card-id "c"
                                  :tree clean-tree
                                  :producers [threshold-producer]})))))))

(deftest a-supplied-threshold-is-namespaced-by-producer-id
  (is (= "gap=7"
         (:detail (first (:live (findings/card-findings
                                 {:card-id "c"
                                  :tree clean-tree
                                  :producers [threshold-producer]
                                  :thresholds {:demo/gap-px 7}})))))))

(deftest an-unknown-threshold-key-throws
  (testing "silently ignoring it would leave the consumer believing a
            stricter gate was armed than the one that ran"
    (is (thrown? Exception
                 (findings/card-findings {:card-id "c"
                                          :tree clean-tree
                                          :producers [threshold-producer]
                                          :thresholds {:demo/gap-pixels 7}})))))

(deftest a-threshold-value-failing-its-predicate-throws
  (is (thrown? Exception
               (findings/card-findings {:card-id "c"
                                        :tree clean-tree
                                        :producers [threshold-producer]
                                        :thresholds {:demo/gap-px -1}}))))

(deftest two-producers-may-each-own-a-gap-px
  (testing "namespacing by producer id is what lets rules pick natural
            threshold names without colliding"
    (let [other (assoc threshold-producer :id :other)
          res (findings/card-findings {:card-id "c"
                                       :tree clean-tree
                                       :producers [threshold-producer other]
                                       :thresholds {:demo/gap-px 1 :other/gap-px 2}})]
      (is (= ["gap=1" "gap=2"] (mapv :detail (:live res)))))))

;; ── registration-time refusals ───────────────────────────────────────────

(deftest an-empty-producer-set-throws
  (testing "a gate with no rules passes everything and proves nothing"
    (is (thrown? Exception
                 (findings/card-findings {:card-id "c"
                                          :tree clean-tree
                                          :producers []})))))

(deftest duplicate-producer-ids-throw
  (is (thrown? Exception
               (findings/validate-producers! [(noop-producer :dup) (noop-producer :dup)]))))

(deftest an-unknown-requires-key-throws-at-registration
  (testing "a typo'd requirement must fail when the rule is registered, not
            silently never be checked at judgment time"
    (is (thrown? Exception
                 (findings/validate-producers! [(noop-producer :p #{:proxyrects})])))))

(deftest a-producer-emitting-a-malformed-finding-throws
  (testing "the extension point is validated: :card and :invariant are what
            every downstream lane reads"
    (is (thrown? Exception
                 (findings/card-findings
                  {:card-id "c"
                   :tree clean-tree
                   :producers [{:id :bad
                                :fn (fn [_] [{:detail "no card, no invariant"}])
                                :requires #{}}]})))))

(deftest a-producer-returning-a-non-seq-throws
  (is (thrown? Exception
               (findings/card-findings
                {:card-id "c"
                 :tree clean-tree
                 :producers [{:id :bad :fn (fn [_] {:card "c" :invariant :x}) :requires #{}}]}))))

;; ── exemptions still ride the composed verdict ───────────────────────────

(deftest exemptions-apply-across-every-producer
  (testing "a consumer rule's findings are exemptible on the same
            proof-carrying, ratchet-down terms as the built-in lanes"
    (let [extra {:id :extra
                 :fn (fn [{:keys [card-id]}]
                       [{:card card-id :invariant :custom :detail "x"}])
                 :requires #{}}
          res (findings/card-findings
               {:card-id "c"
                :tree clean-tree
                :producers [extra]
                :exemptions [{:card "c"
                              :invariant :custom
                              :rationale "proven benign for this card"
                              :retires-when "the widget grows its own box"}]})]
      (is (empty? (:live res)))
      (is (= [:custom] (mapv :invariant (:exempted res))))
      (is (empty? (:stale-exemptions res))))))

(deftest an-exemption-matching-nothing-is-itself-a-finding
  (testing "the list can only ratchet down"
    (let [res (findings/card-findings
               {:card-id "c"
                :tree clean-tree
                :emissions {}
                :host-proxy? false
                :caps {:vis-px? true}
                :exemptions [{:card "c"
                              :invariant :never-emitted
                              :rationale "r"
                              :retires-when "w"}]})]
      (is (= [:stale-exemption] (mapv :invariant-class (:stale-exemptions res)))))))

;; ── threshold keys must not collide across namespaced producer ids ───────

(deftest namespaced-producer-ids-get-distinct-threshold-keys
  (testing "discarding an id's namespace collapsed :acme/overlap and
            :bob/overlap onto one key. `into` keeps the LAST spec, so one
            producer's declared :pred stopped being evaluated and a supplied
            value could relax a gate past its own declaration — the exact
            failure the unknown-key refusal exists to prevent."
    (is (not= (findings/threshold-key :acme/overlap :gap-px)
              (findings/threshold-key :bob/overlap :gap-px)))
    (let [mk (fn [id cap]
               {:id id
                :fn (fn [{:keys [card-id thresholds]}]
                      [{:card card-id :invariant :t
                        :detail (str (name id) "=" (:gap-px thresholds))}])
                :requires #{}
                :thresholds {:gap-px {:pred #(<= % cap) :default 0 :doc "d"}}})
          producers [(mk :acme/overlap 3) (mk :bob/overlap 99)]]
      (testing "each id addresses its OWN threshold"
        (is (= ["overlap=1" "overlap=2"]
               (mapv :detail
                     (:live (findings/card-findings
                             {:card-id "c"
                              :tree clean-tree
                              :producers producers
                              :thresholds {(findings/threshold-key :acme/overlap :gap-px) 1
                                           (findings/threshold-key :bob/overlap :gap-px) 2}}))))))
      (testing "and acme's stricter :pred is still enforced — under the
                collision it was silently replaced by bob's"
        (is (thrown? Exception
                     (findings/card-findings
                      {:card-id "c"
                       :tree clean-tree
                       :producers producers
                       :thresholds {(findings/threshold-key :acme/overlap :gap-px) 50}})))))))

(deftest a-lossy-threshold-key-collision-is-caught-at-registration
  (testing "the first fix keyed on the WHOLE producer id but flattened the
            namespace into a dotted segment, which is not injective:
            :acme/overlap and :acme.overlap both yield :acme.overlap/gap-px.
            Proving an encoding injective by inspection is how the first
            collision shipped, so the RESULT is checked instead."
    (is (= (findings/threshold-key :acme/overlap :gap-px)
           (findings/threshold-key :acme.overlap :gap-px))
        "the encoding really is lossy — this is the premise, not the defect")
    (let [mk (fn [id cap]
               {:id id
                :fn (fn [_] [])
                :requires #{}
                :thresholds {:gap-px {:pred #(and (nat-int? %) (<= % cap))
                                      :default 0 :doc "d"}}})]
      (testing "so the collision must throw rather than silently keep one
                producer's spec and drop the other's :pred"
        (is (thrown? Exception
                     (findings/card-findings
                      {:card-id "c"
                       :tree clean-tree
                       :producers [(mk :acme/overlap 4) (mk :acme.overlap 999)]}))))
      (testing "CONTROL: genuinely distinct ids still register and still keep
                their own predicates"
        (is (= [] (:live (findings/card-findings
                          {:card-id "c"
                           :tree clean-tree
                           :producers [(mk :acme/overlap 4) (mk :bob/overlap 999)]}))))))))

;; ── the collision message must be true BY CONSTRUCTION ───────────────────
;; The collision check fires safely, but it once fired naming the wrong cause:
;; a SINGLE producer declaring both :gap-px and :x/gap-px collided with itself
;; (threshold-key uses (name k), discarding the namespace) and was told to
;; "rename one producer id" — advice invariant under the collision. A seq of
;; pairs reached the same wrong message with no namespaces involved at all.
;; Both shapes are now rejected at registration, so the only way to collide is
;; the one the message describes.

(deftest a-namespaced-threshold-key-is-rejected
  (testing "its namespace would be silently discarded by threshold-key, so a
            producer could declare two keys that are the same key"
    (is (thrown? Exception
                 (findings/validate-producers!
                  [{:id :ui
                    :fn (fn [_] [])
                    :requires #{}
                    :thresholds {:gap-px {:pred nat-int? :default 0 :doc "d"}
                                 :x/gap-px {:pred nat-int? :default 0 :doc "d"}}}])))))

(deftest a-non-map-thresholds-is-rejected
  (testing "a seq of pairs destructures fine in doseq, so it registered and
            then collided with itself — reaching the inter-producer message
            with no second producer in sight"
    (is (thrown? Exception
                 (findings/validate-producers!
                  [{:id :ui
                    :fn (fn [_] [])
                    :requires #{}
                    :thresholds [[:gap-px {:pred nat-int? :default 0 :doc "d"}]
                                 [:gap-px {:pred nat-int? :default 1 :doc "d"}]]}])))))

(deftest CONTROL-well-formed-producers-still-register
  (testing "the two refusals above must not have made the ordinary cases fail"
    (testing "a producer WITH several distinct unqualified threshold keys"
      (is (findings/validate-producers!
           [{:id :ui
             :fn (fn [_] [])
             :requires #{}
             :thresholds {:gap-px {:pred nat-int? :default 0 :doc "d"}
                          :other-px {:pred nat-int? :default 1 :doc "d"}}}])))
    (testing "and a producer with NO :thresholds at all — most have none, and
              an over-strict map? check rejected every one of them, builtins
              included. The first cut of this fix did exactly that."
      (is (findings/validate-producers!
           [{:id :ui :fn (fn [_] []) :requires #{}}])))
    (testing "which the builtins themselves must satisfy"
      (is (findings/validate-producers! findings/builtin-producers)))))

;; ── the :emission builtin's BODY, which nothing proved ───────────────────
;; Every card-findings call in the suite passed :emissions {} with
;; :host-proxy? false, for which emission-findings returns [] — so the lane's
;; whole half of "builtin-producers-match-a-direct-call-to-the-two-lanes"
;; compared [] to []. Measured: replacing that producer's :fn with (fn [_] [])
;; left the FULL suite green, while the same mutation on the :tree producer
;; failed three tests. That asymmetry was the tell.

(deftest the-emission-builtin-actually-runs-its-lane
  (testing "a card that emits commands must be reported THROUGH the registry,
            not merely through a direct call to emission-findings"
    (let [res (findings/card-findings
               {:card-id "c"
                :tree clean-tree
                :emissions {:commands [{:x 1}] :reports [] :events []}
                :host-proxy? false
                :caps {:vis-px? true}})
          live (:live res)]
      (is (= [:unexpected-emission] (mapv :invariant live)))
      (is (= [:emission] (mapv :producer live))
          "and it is attributed to the emission producer, not the tree lane"))))

(deftest the-host-proxy-arm-is-load-bearing
  (testing "the POSITIVE contract — a host_proxy card must emit EXACTLY one
            proxy-report — is what :host-proxy? true selects. Without a case
            that passes TRUE, hardcoding the flag to false kept the suite
            green."
    (let [live (:live (findings/card-findings
                       {:card-id "c"
                        :tree clean-tree
                        :emissions {:proxy-reports []}
                        :host-proxy? true
                        :caps {:vis-px? true}}))]
      (is (= [:proxy-report-contract] (mapv :invariant live))))
    (testing "CONTROL: the same card with its one proxy-report is clean, so the
              assertion above keys on the missing report, not on the flag"
      (is (empty? (:live (findings/card-findings
                          {:card-id "c"
                           :tree clean-tree
                           :emissions {:proxy-reports [{:id "px"}]}
                           :host-proxy? true
                           :caps {:vis-px? true}})))))))

(deftest an-absent-or-unusable-card-id-throws
  (testing "every finding is keyed to a card, every exemption matches on that
            key, and the verdict is reported per card. An absent :card-id
            yields findings keyed to nil that no exemption can match and no
            report can attribute — a run that LOOKS like it judged something.
            This was the one refusal in the set with no canary."
    (is (thrown? Exception (findings/card-findings
                            {:tree clean-tree
                             :caps {:vis-px? true}
                             :producers [(findings/builtin-producer :tree)]})))
    (is (thrown? Exception (findings/card-findings
                            {:card-id nil
                             :tree clean-tree
                             :caps {:vis-px? true}
                             :producers [(findings/builtin-producer :tree)]})))
    (testing "and a non-nameable id is refused too — an id that cannot be
              printed back is as unattributable as a missing one"
      (is (thrown? Exception (findings/card-findings
                              {:card-id 42
                               :tree clean-tree
                               :caps {:vis-px? true}
                               :producers [(findings/builtin-producer :tree)]})))))
  (testing "CONTROL: both accepted shapes register, so the throw keys on the
            id being unusable and not on the call shape"
    (doseq [id ["c" :c]]
      (is (empty? (:live (findings/card-findings
                          {:card-id id
                           :tree clean-tree
                           :caps {:vis-px? true}
                           :producers [(findings/builtin-producer :tree)]})))))))

;; ── the by-mode emission lane ────────────────────────────────────────────
;; A card rendered in SEVERAL modes, every mode judged. It had no test at
;; all, which is how the empty-map silence below survived registration.

(defn- by-mode
  [emissions-by-mode host-proxy?]
  (:live (findings/card-findings
          {:card-id "c"
           :emissions-by-mode emissions-by-mode
           :host-proxy? host-proxy?
           :producers [findings/emission-by-mode-producer]})))

(deftest an-empty-emissions-by-mode-map-throws
  (testing "judging zero modes passes the card without running one contract,
            and the output is byte-identical to a clean judgement — the
            precise silence this registry refuses. (sort-by key {}) is empty,
            the mapcat yields [], and check-requires! is satisfied because {}
            is some?, so nothing upstream catches it."
    (is (thrown? Exception (by-mode {} false))))
  (testing "and the host_proxy case is the one that matters: with the POSITIVE
            arm selected, an empty map means the 'must emit exactly one
            proxy-report' contract never runs and the card passes CLEAN"
    (is (thrown? Exception (by-mode {} true)))))

(deftest a-non-empty-mode-map-is-still-judged
  (testing "CONTROL for the refusal above — the throw must key on emptiness,
            not on the shape. A single mode with nothing captured is a real
            claim and passes."
    (is (empty? (by-mode {:dark {:commands [] :reports [] :events []}} false))))
  (testing "every mode is judged, not just the first"
    (let [live (by-mode {:dark {:commands [] :reports [] :events []}
                         :light {:commands [{:id "c1"}] :reports [] :events []}}
                        false)]
      (is (= [:unexpected-emission] (mapv :invariant live)))
      (testing "and the finding names WHICH render emitted — a merged lane
                cannot say that, which is why this is a separate producer"
        (is (= :light (:mode (first live))))))))

(deftest the-by-mode-lane-carries-the-host-proxy-arm
  (testing "the POSITIVE contract must survive the by-mode wrapper: a
            host_proxy card whose mode emitted no proxy-report fires"
    (is (= [:proxy-report-contract]
           (mapv :invariant (by-mode {:dark {:proxy-reports []}} true)))))
  (testing "CONTROL: the same mode with its one report is clean, so the
            assertion keys on the missing report and not on the flag"
    (is (empty? (by-mode {:dark {:proxy-reports [{:id "px"}]}} true)))))

(deftest a-threshold-default-must-satisfy-its-own-pred
  (testing "the default is what runs when the consumer supplies nothing, so it
            is the value MOST runs use — and it was the one value exempt from
            the producer's own predicate"
    (is (thrown? Exception
                 (findings/validate-producers!
                  [{:id :ui :fn (fn [_] []) :requires #{}
                    :thresholds {:gap-px {:pred nat-int? :default -1 :doc "d"}}}]))))
  (testing "CONTROL: a default that SATISFIES the pred still registers"
    (is (findings/validate-producers!
         [{:id :ui :fn (fn [_] []) :requires #{}
           :thresholds {:gap-px {:pred nat-int? :default 0 :doc "d"}}}])))
  (testing "and a pred that THROWS on the default is a failure, not a pass —
            the check must not let an exception read as satisfied"
    (is (thrown? Exception
                 (findings/validate-producers!
                  [{:id :ui :fn (fn [_] []) :requires #{}
                    :thresholds {:gap-px {:pred #(pos? %) :default nil :doc "d"}}}])))))
