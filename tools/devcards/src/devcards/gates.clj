(ns devcards.gates
  "The corpus-level contract gates — pure logic over per-card render hashes.

   Inputs: the parsed corpus spec (each card carries :expect — :baseline /
   :distinct / :inert / :probe / :probe-defect / :probe-pixel-only) and hash
   maps {card-id → sha256-hex} per theme family. Outputs: finding vectors;
   empty = green.

   The lanes:
   - COVERAGE: every spec card rendered (a missing hash is a finding — a
     gate over a partial corpus must say so, never pass vacuously) + every
     manifest widget class has cards.
   - STATE CONTRACT: a :distinct card's hash ≠ its baseline counterpart
     (DISTINCTNESS — a committed state must be visibly styled); an :inert
     card's hash == its baseline (INERTNESS — an uncommitted state must
     change nothing). Baseline resolution is BY ID (state segment →
     'default', same tag/size/value — the value-axis-aware reference rule),
     and an unresolvable baseline is a spec-defect finding, never a skip.
     :probe* cards are excluded from both directions — their semantics live
     in the invariants lane (devcards.invariants) and the pixel gallery.
   - VANILLA≡STOCK: per card, family-1 hash == family-2 hash (the theme's
     idempotency contract at corpus scale).
   - INERT-PROP (composition lane): an :inert-prop card's hash == its
     declared :base-card sibling's — an interaction-only prop (the
     scrubber's seek_on_press) must move ZERO pixels."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- card-index
  "{card-id → {:card <card> :tag <widget-tag>}} over the whole spec."
  [spec]
  (into {} (for [w (:widgets spec) c (:cards w)] [(str (:id c)) {:card c :tag (:tag w)}])))

(defn baseline-id
  "The reference card id for a state cell: the same cell with its state
   segment replaced by 'default' (tag/state/size[/value])."
  ^String [^String card-id]
  (let [parts (str/split card-id #"/")]
    (when (< (count parts) 3)
      (throw (ex-info "card id is not tag/state/size[/value]" {:id card-id})))
    (str/join "/" (assoc parts 1 "default"))))

(defn coverage-findings
  "Every spec card must have a hash; every widget must have cards."
  [spec hashes]
  (let [idx (card-index spec)]
    (-> []
        (into (for [[id _] idx
                    :when (not (contains? hashes id))]
                {:gate :coverage :card id :detail "card never rendered"}))
        (into (for [w (:widgets spec)
                    :when (empty? (:cards w))]
                {:gate :coverage :card (:tag w) :detail "widget class has ZERO cards"})))))

(defn state-contract-findings
  "DISTINCTNESS + INERTNESS over one family's hashes (the asgard family is
   the styled one the contract judges)."
  [spec hashes]
  (let [idx (card-index spec)]
    (vec
     (for [[id {:keys [card]}] (sort-by key idx)
           :let [expect (:expect card)]
           :when (contains? #{:distinct :inert} expect)
           :let [base-id (baseline-id id)
                 base-hash (get hashes base-id)
                 hash (get hashes id)
                 finding (cond (nil? hash) nil ; coverage lane owns missing renders
                               (nil? base-hash)
                               {:gate :state-contract
                                :card id
                                :detail (str "baseline card "
                                             base-id
                                             " missing from spec/renders — spec defect")}
                               (and (= expect :distinct) (= hash base-hash))
                               {:gate :distinctness
                                :card id
                                :detail (str "committed state renders IDENTICAL to "
                                             base-id
                                             " — the theme does not style it")}
                               (and (= expect :inert) (not= hash base-hash))
                               {:gate :inertness
                                :card id
                                :detail (str "uncommitted state renders DIFFERENT from "
                                             base-id
                                             " — undeclared styling")}
                               :else nil)]
           :when finding]
       finding))))

(defn vanilla-stock-findings
  "Per-card family-1 vs family-2 hash equality — the corpus-scale
   idempotency gate. Takes the two family hash maps; judges every card
   present in BOTH (coverage owns absences)."
  [vanilla-hashes stock-hashes]
  (vec
   (for [[id v-hash] (sort-by key vanilla-hashes)
         :let [s-hash (get stock-hashes id)]
         :when (and s-hash (not= v-hash s-hash))]
     {:gate :vanilla-stock
      :card id
      :detail
      "family 1 (vanilla) != family 2 (stock) — a vanilla arm
               drifted from the stock formula it restates"})))

(defn inert-prop-findings
  "The composition lane's prop-inertness pin: an :inert-prop card must
   hash IDENTICAL to its declared :base-card sibling (an interaction-only
   prop moves zero pixels). `cards` = built composition entries
   ({:id :expect :base-card}); `hashes` = {card-id → sha256-hex} for one
   mode. A missing render is a finding — never a vacuous pass."
  [cards hashes]
  (vec
   (for [{:keys [id expect base-card]} cards
         :when (= :inert-prop expect)
         :let [h (get hashes (str id))
               bh (get hashes (str base-card))
               finding (cond (nil? h) {:gate :inert-prop
                                       :card (str id)
                                       :detail "card never rendered"}
                             (nil? bh) {:gate :inert-prop
                                        :card (str id)
                                        :detail (str "base card "
                                                     base-card
                                                     " never rendered")}
                             (not= h bh) {:gate :inert-prop
                                          :card (str id)
                                          :detail (str "renders DIFFERENT from "
                                                       base-card
                                                       " — an interaction-only prop"
                                                       " moved pixels")}
                             :else nil)]
         :when finding]
     finding)))

(defn run-gates
  "All lanes. `family-hashes` = {0 {id→hash} 1 {...} 2 {...}} (asgard /
   vanilla / stock). Returns {:findings [...] :counts {...}} — the caller
   fails on any finding."
  [spec family-hashes]
  (let [asgard (get family-hashes 0)
        findings (-> []
                     (into (coverage-findings spec asgard))
                     (into (state-contract-findings spec asgard))
                     (into (vanilla-stock-findings (get family-hashes 1)
                                                   (get family-hashes 2))))]
    {:findings findings
     :counts {:cards (count (card-index spec))
              :findings (count findings)
              :by-gate (frequencies (map :gate findings))}}))