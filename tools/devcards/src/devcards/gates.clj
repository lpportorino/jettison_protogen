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
     scrubber's seek_on_press) must move ZERO pixels.
   - SECRET-SCAN: no card carries an absolute system/device path, a credential
     assignment or token shape, or a non-placeholder proxy id. The corpus is
     PUBLIC and its fixtures are gate-held secret-free; this is the lane CI
     advertises. Runs over EVERY card population — atomic, kitchen-sink, and
     composition."
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

;; ── secret-scan ─────────────────────────────────────────────────────────
;; The corpus is PUBLIC and its fixtures are GATE-HELD secret-free (CLAUDE.md:
;; generic widgets only; proprietary device meta stays in the private
;; consumers). This lane enforces the threat model the CI job advertises:
;; absolute paths, credential-shaped tokens, non-placeholder proxy ids.
(def ^:private abs-path-re
  "A POSIX absolute path into a system/device/user dir, ANYWHERE in the string.
   (?im) is load-bearing twice: paths appear mid-string (\"dump written to
   /home/…\") and the corpus authors multi-line values (dropdown :options
   \"Auto\\nManual\\nOff\"), so a start-of-STRING anchor would miss both. The
   leading boundary keeps the corpus's LVGL drive-letter convention
   (\"P:icons/x.svg\") out — it has no /systemdir/ segment at all."
  #"(?im)(?:^|[^\w])/(home|users|root|etc|var|opt|mnt|srv|tmp|dev|proc|sys|media|run)/")

(def ^:private credential-re
  "A credential ASSIGNMENT (key: value / key=value) or a known token shape.
   Deliberately NOT a bare keyword match: \"Password\" is legitimate widget text
   (ui_ast carries a password_mode prop, so a textarea card will one day render
   it) and flagging it would make the gate cry wolf — the disable-it-and-protect
   -nothing failure this lane exists to avoid. The token shapes are the ones a
   real leak looks like."
  #"(?i)(\b(?:secret|password|passwd|api[-_]?key|access[-_]?token|bearer|credential)s?\s*[:=]\s*\S|\bghp_[A-Za-z0-9]{20,}|\bAKIA[0-9A-Z]{16}\b|\bxox[baprs]-[A-Za-z0-9-]{10,})")

(def ^:private proxy-placeholder
  "The ONE sanctioned host_proxy id: a placeholder, never a real device id."
  "px")

(defn- card-strings
  "Every string reachable in `card`. Scans the WHOLE card, prose included: the
   corpus file IS the public artifact, so a device landmark pasted into a card's
   :notes ships just as surely as one in rendered content."
  [card]
  (->> card (tree-seq coll? seq) (filter string?)))

(defn- proxy-ids
  "Every :proxy_id value reachable in `card`."
  [card]
  (->> (tree-seq coll? seq card) (filter map?) (keep :proxy_id)))

(defn corpus-secret-findings
  "SECRET-SCAN over a seq of CARDS: none may carry an absolute system/device
   path, a credential assignment or known token shape, or a proxy id that is not
   the documented placeholder. Pure — cards in, findings out; empty = green.
   Takes a card SEQ (not the spec) so every card population is scannable: the
   atomic widget cards, the kitchen sinks, and the composition inventory."
  [cards]
  (vec
   (for [c cards
         :let [id (str (or (:id c) (:tag c) "?"))]
         finding (concat
                  (for [s (card-strings c)
                        :let [detail (cond
                                       (re-find abs-path-re s)
                                       (str "absolute path: " (pr-str s))
                                       (re-find credential-re s)
                                       (str "credential assignment/token: " (pr-str s)))]
                        :when detail]
                    {:gate :secret-scan :card id :detail detail})
                  (for [p (proxy-ids c)
                        :when (not= proxy-placeholder p)]
                    {:gate :secret-scan
                     :card id
                     :detail (str "non-placeholder proxy_id " (pr-str p)
                                  " — the corpus uses the literal "
                                  (pr-str proxy-placeholder))}))]
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
                     (into (corpus-secret-findings
                            (concat (mapcat :cards (:widgets spec))
                                    (:kitchen-sinks spec))))
                     (into (vanilla-stock-findings (get family-hashes 1)
                                                   (get family-hashes 2))))]
    {:findings findings
     :counts {:cards (count (card-index spec))
              :findings (count findings)
              :by-gate (frequencies (map :gate findings))}}))