(ns devcards.findings-test
  "Contract tests for the finding-producer registry (`devcards.findings`).

   The registry's job is not to run rules — it is to make the ways a rule
   can go WRONG loud. Every refusal below exists because its silent
   alternative produces output identical to a clean run: an empty producer
   set, a rule that never received its input, a threshold typo that
   relaxes the gate it names. Those are the failures a green gate cannot
   distinguish from success, so each one throws."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]
            [devcards.outcome :as outcome]
            [devcards.overlap :as overlap]))

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
;; against protogen's own gate, which routes through the registry but with a
;; different producer vector — `devcards.expect/tree-producer` for the :expect
;; routing, and `emission-by-mode-producer` for the dark/light pair. Those are
;; pinned in devcards.expect-test; nothing here certifies them.

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

;; ── the ACT/EARL declaration ─────────────────────────────────────────────
;; The vocabulary alone would let a future author soften a definite defect
;; into a doubt in a four-character diff. These pin the DECLARATION that
;; makes that a registration-time throw instead — and, first, the thing the
;; declaration must never cost: the OPEN finding map every producer written
;; before these axes was allowed to return.

(defn- emit
  "A producer that emits exactly `f` (merged onto a well-formed finding)."
  [p f]
  (findings/card-findings
   {:card-id "c"
    :tree clean-tree
    :producers [(merge {:id :probe :requires #{}
                        :fn (fn [{:keys [card-id]}]
                              [(merge {:card card-id :invariant :x} f)])}
                       p)]}))

(defn- msg
  "The message of whatever `f` throws, or nil. `thrown?` alone cannot tell a
   clause from its neighbour, which is how a decorative canary survives."
  [f]
  (try (f) nil (catch Throwable t (ex-message t))))

;; ── the compatibility floor: a legacy producer keeps its OPEN map ────────

(deftest a-producer-DECLARING-NO-AXES-may-still-return-an-open-map
  (testing "before these axes existed, `check-findings!` required :card and
            :invariant and permitted every other key, and the VLM review's
            own briefing documents the shape as open. protogen is trunk-only
            upstream for ten-plus consumer repos, so reserving the three
            plainest words in the vocabulary — :outcome, :test-mode, :reason
            — would have been a MIGRATION every consumer answered at its next
            pin bump, and :reason is the likely one in the wild: it is the
            natural name for 'why this fired', and the gap a consumer would
            have filled locally with its own :severity + :reason.
            REVERT-TO-BREAK: read the unnamespaced keys in `check-outcome!`."
    (doseq [payload [{:reason "the label overflows its box"}
                     {:outcome "suppressed by hand"}
                     {:test-mode :manual}
                     {:severity :minor :confidence 0.4}]]
      (is (= [:x] (mapv :invariant (:live (emit {} payload))))
          (pr-str payload))))
  (testing "and the payload SURVIVES onto the finding rather than being
            quietly normalised away — a silent overwrite would be data loss
            dressed as compatibility"
    (is (= "the label overflows its box"
           (:reason (first (:live (emit {} {:reason "the label overflows its box"})))))))
  (testing "CONTROL: a producer that OPTED IN is held to the namespaced
            spelling, so the tolerance above is scoped to the population that
            could not have known about it — and no producer that exists today
            opts in"
    (is (str/includes?
         (str (msg #(emit {:outcomes #{:failed}} {:reason "v"})))
         "the finding axis is :act/reason"))
    (is (str/includes?
         (str (msg #(emit {:test-mode :automatic} {:outcome :failed})))
         "the finding axis is :act/outcome"))))

;; ── the closed producer key set ──────────────────────────────────────────

(deftest the-producer-key-set-is-CLOSED
  (testing "widening this set from four keys to seven was FREE, because
            nothing pinned it — the one state in which widening a closed set
            costs nothing is the state in which it has no canary. :severity
            is the plausible next widening: it is the axis this repo
            deliberately does not have, and a producer carrying it would look
            armed and do nothing.
            REVERT-TO-BREAK: add :severity to `producer-keys`."
    (is (str/includes?
         (str (msg #(findings/validate-producers!
                     [{:id :p :fn (fn [_] []) :requires #{} :severity :minor}])))
         "unknown keys [:severity]")))
  (testing "CONTROL: the same producer without it registers, so the throw
            keys on the key and not on the producer shape"
    (is (= 1 (count (findings/validate-producers!
                     [{:id :p :fn (fn [_] []) :requires #{}}]))))))

;; ── the declaration is a whitelist ───────────────────────────────────────

(deftest a-geometry-producer-CANNOT-emit-a-doubt-it-never-declared
  (testing "geometry is exact integer arithmetic with no noise floor, so an
            uncertain verdict there manufactures doubt the arithmetic does not
            have. A producer that declares no :outcomes is two-way by
            construction and softening one of its findings throws.
            REVERT-TO-BREAK: delete the (contains? declared o) block."
    (is (str/includes?
         (str (msg #(emit {} {:act/outcome :inapplicable})))
         "declares only [:failed]")))
  (testing "and a producer that declared the DOUBT still cannot emit a
            verdict beside it — the declaration is a whitelist, not a mode"
    (is (str/includes?
         (str (msg #(emit {:outcomes #{:failed :cantTell}
                           :reasons {:noise-band "d"}}
                          {:act/outcome :untested})))
         "declares only [:cantTell :failed]")))
  (testing "and the armed geometry rule really is one of those — this is the
            premise, not the defect"
    (is (not (contains? overlap/producer :outcomes))))
  (testing "CONTROL: the same producer's ordinary finding, and an explicit
            :failed, both pass — so the throw keys on the undeclared outcome"
    (is (= [:x] (mapv :invariant (:live (emit {} {})))))
    (is (= [:x] (mapv :invariant (:live (emit {} {:act/outcome :failed})))))))

(deftest the-VOCABULARY-is-checked-where-it-can-actually-be-violated
  (testing "a producer's DECLARED set is validated as a subset of the
            vocabulary at registration. That is what made the finding-side
            'not in the ACT vocabulary' clause unreachable — (contains?
            declared o) implies it — and deleting that clause left the suite
            green, because both of its assertions threw from neighbouring
            clauses. It is gone; this pins the clause that is real.
            REVERT-TO-BREAK: delete the (every? outcome/outcomes outs) test
            from `validate-producers!`."
    (is (str/includes?
         (str (msg #(emit {:outcomes #{:failed :cantTel}} {})))
         ":outcomes must be a non-empty subset of")))
  (testing "and the finding side of the vocabulary is checked at the VERDICT
            seam instead, which is the only place the corpus gates and the
            interaction lane can be reached at all — pinned in
            devcards.outcome-test"
    (is (some? (outcome/axis-problem {:producer :x :act/outcome :cantTel}))))
  (testing "CONTROL: the well-formed declaration registers"
    (is (= [:x] (mapv :invariant
                      (:live (emit {:outcomes #{:failed :cantTell}
                                    :reasons {:noise-band "in the noise"}}
                                   {})))))))

;; ── :passed may not even be DECLARED ─────────────────────────────────────

(deftest a-producer-may-not-declare-passed
  (testing "the cheapest laundering path this design had: +1 keyword in
            :outcomes and +1 on the finding bought a non-blocking real
            defect, owing no reason, no doc, no :rationale, no :retires-when
            and no policy edit — making the least-reviewed path to a green
            gate also the easiest one. It is a category error besides: this
            vector has no per-(rule, target) result model, so :passed on a
            reported finding can only mean 'suppressed'.
            REVERT-TO-BREAK: delete the `unreportable-outcomes` block from
            `validate-producers!`."
    (is (str/includes?
         (str (msg #(emit {:outcomes #{:failed :passed}} {})))
         "which a FINDING may never carry")))
  (testing "CONTROL: the other four values declare fine, so the throw keys on
            :passed and not on declaring a second outcome"
    (is (= [:x] (mapv :invariant
                      (:live (emit {:outcomes #{:failed :cantTell :inapplicable
                                                :untested}
                                    :reasons {:r "why"}}
                                   {})))))))

;; ── every non-default outcome owes a DECLARED reason ─────────────────────

(deftest every-outcome-that-is-not-failed-owes-a-reason-vocabulary
  (testing "axe-core's incomplete-data set(key,reason) validates only that
            the key is a string, so a typo is a new reason. The concept is
            worth keeping; that implementation is not. Widened from :cantTell
            alone because :inapplicable and :untested are non-blocking by
            shape too, so leaving them reason-free left two outcomes a real
            defect could be relabelled into for free. They are also the two
            halves of the same obligation: :inapplicable says the clause ran
            and this target was out of scope, :untested says the clause did
            not run here — and each is evidence only if it says WHICH.
            REVERT-TO-BREAK: narrow the if-let back to (contains? outs
            :cantTell)."
    (doseq [o [:cantTell :inapplicable :untested]]
      (is (str/includes? (str (msg #(emit {:outcomes #{:failed o}} {})))
                         "no non-empty :reasons map")
          (str o))))
  (testing "and a reason vocabulary with nothing to explain is the inverse
            mistake — a declaration that names nothing"
    (is (str/includes? (str (msg #(emit {:reasons {:noise-band "d"}} {})))
                       ":reasons but :outcomes names none of")))
  (testing "a bare set is not enough either: a reason with no stated meaning
            names the gap without saying what would close it"
    (is (str/includes?
         (str (msg #(emit {:outcomes #{:failed :cantTell}
                           :reasons {:noise-band "  "}} {})))
         "needs a non-blank doc")))
  (testing "and a namespaced reason key is refused — the producer id names it
            already"
    (is (str/includes?
         (str (msg #(emit {:outcomes #{:failed :cantTell}
                           :reasons {:acme/noise-band "d"}} {})))
         "must be an UNQUALIFIED keyword")))
  (testing "CONTROL: the well-formed declaration registers and judges"
    (is (= [:x] (mapv :invariant
                      (:live (emit {:outcomes #{:failed :cantTell}
                                    :reasons {:noise-band "measured ratio and
                                              threshold are closer than the
                                              measurement's own noise"}}
                                   {})))))))

(deftest a-non-answer-must-name-a-DECLARED-reason
  (let [contrast {:outcomes #{:failed :cantTell :inapplicable :untested}
                  :reasons {:noise-band "in the noise"
                            :mask-absent "no glyph mask supplied"}}]
    (testing "'the score is in the noise band', 'there is no glyph ink here'
              and 'no mask was supplied' are three different non-answers; one
              that will not say which it is has re-fused them.
              REVERT-TO-BREAK: delete the (contains? reasons …) block."
      (doseq [o [:cantTell :inapplicable :untested]]
        (is (str/includes? (str (msg #(emit contrast {:act/outcome o})))
                           "its declared reasons are [:mask-absent :noise-band]")
            (str o))
        (is (some? (msg #(emit contrast {:act/outcome o
                                         :act/reason :noize-band})))
            (str o " typo"))))
    (testing "and a reason on a :failed is refused — the vocabulary belongs to
              the non-answers and nothing else"
      (is (str/includes?
           (str (msg #(emit contrast {:act/outcome :failed
                                      :act/reason :noise-band})))
           "reason vocabulary belongs to")))
    (testing "CONTROL: a declared reason on each non-answer passes, so every
              throw above keys on its own clause"
      (doseq [o [:cantTell :inapplicable :untested]]
        (is (= [:noise-band]
               (mapv :act/reason
                     (:live (emit contrast {:act/outcome o
                                            :act/reason :noise-band}))))
            (str o))))))

;; ── the mode axis belongs to the producer ────────────────────────────────

(deftest the-mode-axis-belongs-to-the-PRODUCER
  (testing "a finding that could name its own mode would let a
            non-reproducible lane claim :automatic one finding at a time,
            which is exactly what the axis exists to make impossible.
            REVERT-TO-BREAK: delete the (contains? f :act/test-mode) block."
    (is (str/includes? (str (msg #(emit {} {:act/test-mode :automatic})))
                       "the mode is the PRODUCER's"))
    (is (str/includes?
         (str (msg #(emit {:test-mode :manual} {:act/test-mode :manual})))
         "the mode is the PRODUCER's")))
  (testing "an undeclarable mode is refused at registration"
    (is (str/includes? (str (msg #(emit {:test-mode :semi-auto} {})))
                       ":test-mode must be one of")))
  (testing "a :manual producer's findings come back STAMPED, so the verdict
            can keep a non-reproducible lane out of the exit code"
    (is (= [:manual]
           (mapv :act/test-mode (:live (emit {:test-mode :manual} {}))))))
  (testing "and an :automatic producer's findings carry NO axis key at all —
            every producer in the fleet is automatic today, so the persisted
            findings vector stays byte-identical"
    (let [f (first (:live (emit {} {})))]
      (is (empty? (filter #(contains? f %) outcome/axis-keys))))))

;; ── the exemption vocabulary door ────────────────────────────────────────

(deftest an-exemption-may-not-name-an-UNDECLARED-reason
  (let [contrast {:id :contrast
                  :requires #{}
                  :outcomes #{:failed :cantTell}
                  :reasons {:noise-band "in the noise"}
                  :fn (fn [{:keys [card-id]}]
                        [{:card card-id :invariant :contrast
                          :act/outcome :cantTell :act/reason :noise-band}])}
        judge (fn [reason]
                (findings/card-findings
                 {:card-id "c"
                  :tree clean-tree
                  :producers [contrast]
                  :exemptions [{:card "c" :invariant :contrast
                                :act/outcome :cantTell :act/reason reason
                                :rationale "no mask emitter for this class yet"
                                :retires-when "the mask emitter lands"}]}))]
    (testing "an exemption naming a reason no ARMED producer declares matches
              nothing, and the stale-exemption ratchet that would catch it is
              dropped by every lane in this repo — so it would be silent.
              REVERT-TO-BREAK: delete the `vocab` doseq in `card-findings`."
      (is (str/includes? (str (msg #(judge :mask-absent)))
                         "no armed producer declares it")))
    (testing "CONTROL: the declared reason exempts, proving the throw is the
              vocabulary check and not the exemption path being broken"
      (let [res (judge :noise-band)]
        (is (empty? (:live res)))
        (is (= [:contrast] (mapv :invariant (:exempted res))))
        (is (empty? (:stale-exemptions res)))))))

;; ── END TO END: a registered producer through to the exit code ───────────

(deftest a-REGISTERED-cantTell-reaches-the-EXIT-CODE
  (testing "the registry half of this suite stopped at (:live …) and the
            verdict half started from a hand-built vector, so the path the
            whole axis exists for — a producer declares :cantTell, emits one,
            and the process fails on it — was pinned at neither end.
            REVERT-TO-BREAK: drop :cantTell from `default-fail-outcomes`, or
            stop stamping in `card-findings`.

            IT RUNS THROUGH `lanes/run-verdict`, the fn `devcards.core` calls.
            Through `outcome/exit-code` this test was closed against a fn with
            no production caller: forcing :exit 0 in the gate's own
            computation left it green."
    (let [contrast {:id :contrast :requires #{}
                    :outcomes #{:failed :cantTell}
                    :reasons {:noise-band "in the noise"}
                    :fn (fn [{:keys [card-id]}]
                          [{:card card-id :invariant :contrast
                            :act/outcome :cantTell :act/reason :noise-band}])}
          live (:live (findings/card-findings {:card-id "c" :tree clean-tree
                                               :producers [contrast]}))]
      (is (= 1 (count live)))
      (is (nil? (outcome/axis-problem (first live)))
          "the registry's own output must satisfy the verdict's entitlement
           check, or the two halves disagree about the same finding")
      (is (= 1 (:exit (lanes/run-verdict live))))
      (is (zero? (:exit (lanes/run-verdict [])))
          "CONTROL: the same fn over an empty vector exits 0, so the one
           above is the finding and not a constant")
      (is (= live (outcome/blocking live outcome/default-policy)))
      (testing "and under a narrowed policy the SAME live vector is advisory"
        (is (zero? (outcome/exit-code
                    live
                    {:fail-outcomes #{:failed} :fail-modes #{:automatic}
                     :rationale "arming the contrast lane on a fresh corpus"
                     :retires-when "the corpus is clean under the default"})))))))

(deftest a-REGISTERED-manual-producer-is-reported-and-never-blocks
  (testing "the VLM review's whole disposition, end to end: it rides the
            registry, it is stamped :manual, it lands in the vector, and it
            does not set the exit code.
            REVERT-TO-BREAK: stop stamping :act/test-mode in `card-findings`.

            Also through `lanes/run-verdict` — the exit code this asserts on
            has to be the one the process leaves with."
    (let [vlm {:id :vlm-review :requires #{} :test-mode :manual
               :fn (fn [{:keys [card-id]}]
                     [{:card card-id :invariant :vlm/legibility-doubt
                       :detail "the numerals sit on a busy plate"}])}
          live (:live (findings/card-findings {:card-id "c" :tree clean-tree
                                               :producers [vlm]}))]
      (is (= [:manual] (mapv :act/test-mode live)))
      (is (zero? (:exit (lanes/run-verdict live))))
      (is (seq live) "the CONTROL for the zero above — an empty vector would
                      satisfy it vacuously and for the wrong reason")
      (testing "CONTROL: the identical producer declared :automatic DOES set
                the exit code, so the zero keys on the declared mode"
        (let [auto (:live (findings/card-findings
                           {:card-id "c" :tree clean-tree
                            :producers [(dissoc vlm :test-mode)]}))]
          (is (= 1 (:exit (lanes/run-verdict auto)))))))))
