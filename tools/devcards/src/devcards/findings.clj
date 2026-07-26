(ns devcards.findings
  "The finding-producer REGISTRY — how a consumer adds a rule to the
   devcard gate without patching this repo.

   protogen owns the ui_ast contract and its reference interpreter; the
   private consumers own their screens. A consumer that finds a new class
   of interface defect must be able to gate on it against ITS corpus,
   here, without forking the runner — otherwise the rule lands in the
   consumer and the next consumer rediscovers the defect from scratch.

   A PRODUCER is a map:

     {:id         :overlap                  ; unique keyword, names its findings
      :fn         (fn [ctx] -> [finding …]) ; pure: context in, findings out
      :requires   #{:tree :classes}         ; ctx inputs it cannot work without
      :thresholds {:gap-px {:pred nat-int? :default 0 :doc \"…\"}}}

   `:fn` receives ONE context map, never a bare tree. That signature is
   load-bearing, not ceremony — a rule like the layer contract needs the
   tree, the consumer's z DECLARATION, and the host-proxy RECTS together,
   because the compositor punches proxy surfaces after LVGL has finished
   and actual stacking is therefore NOT readable from the widget tree's
   child order. A producer contract shaped `(fn [tree] …)` cannot express
   that check at all, so the context is the contract.

   The context, assembled once per card:

     :card-id      the card's id (every finding carries it)
     :tree         the parsed dump_tree root
     :nodes        `invariants/annotate-tree` output — every node with its
                   :path, so ancestry is a prefix test rather than a
                   re-walk each producer does differently
     :emissions    captured host lanes
     :host-proxy?  the one card class whose proxy-report is positive
     :caps         what the loaded module can express, e.g. {:vis-px? true}
     :classes      the consumer's `devcards.classify` table
     :declaration  consumer INTENT (layer z, roles) — never derived from
                   what currently renders
     :proxy-rects  compositor rects for host-proxy surfaces
     :thresholds   this producer's own thresholds, defaults resolved

   Three refusals keep a green run from meaning 'nothing ran':
   - an EMPTY producer set throws (a gate with no rules is not a pass);
   - a producer whose `:requires` key was not SUPPLIED throws, and
     supplied-but-empty is distinct from absent — a consumer asserting
     'this card has no proxy surfaces' passes `:proxy-rects []`, which is
     a claim, where omitting the key is an oversight;
   - an unknown threshold key throws instead of falling back to a
     default, so a typo cannot silently relax the gate it names."
  (:require [clojure.string :as str]
            [devcards.invariants :as invariants]))

(set! *warn-on-reflection* true)

(def ^:private producer-keys #{:id :fn :requires :thresholds})

(def ^:private context-keys
  "Every ctx input a producer may declare in :requires. Closed, so a
   typo'd requirement fails at registration rather than at judgment.

   :expect and :emissions-by-mode exist because protogen's own gate needs
   them, but neither is protogen-specific: a corpus that declares what a
   card is FOR (a probe that must exhibit a defect judges inverted), and a
   corpus rendered in several MODES whose emissions must each be judged,
   are both ordinary shapes. Keeping them here rather than opening the set
   to arbitrary keys preserves the property the set exists for — a typo'd
   :requires fails at registration instead of silently never matching."
  #{:tree :nodes :emissions :emissions-by-mode :host-proxy? :caps :classes
    :declaration :proxy-rects :expect})

(defn- producer-error
  [producer problem]
  (throw (ex-info (str "malformed finding producer: " problem)
                  {:producer (select-keys producer [:id :requires])})))

(defn validate-producers!
  "Shape check for a producer vector: unique keyword :id, callable :fn,
   :requires a subset of `context-keys`, :thresholds a map of keyword ->
   {:pred :default :doc}. Throws on the first problem; returns the vector."
  [producers]
  (when (empty? producers)
    (throw (ex-info "refusing an EMPTY producer set — a gate with no rules
                     passes everything and proves nothing"
                    {})))
  (doseq [p producers]
    (when-not (map? p) (producer-error p "not a map"))
    (when-let [extra (seq (remove producer-keys (keys p)))]
      (producer-error p (str "unknown keys " (vec extra))))
    (when-not (keyword? (:id p)) (producer-error p ":id must be a keyword"))
    (when-not (ifn? (:fn p)) (producer-error p ":fn must be callable"))
    (when-let [bad (seq (remove context-keys (:requires p)))]
      (producer-error p (str ":requires names unknown context keys " (vec bad)
                             " — known: " (vec (sort context-keys)))))
    ;; :thresholds must be a MAP, and each key a SIMPLE keyword. Both checks
    ;; exist to make the collision message below true by construction rather
    ;; than by hope. `threshold-key` builds the consumer key from (name k),
    ;; discarding any namespace on k — so one producer declaring both :gap-px
    ;; and :x/gap-px would map both onto the same consumer key and trip the
    ;; "two producers" error, which names the wrong cause and prescribes a fix
    ;; (rename a producer id) that cannot possibly work. A seq of pairs slipped
    ;; through for the same reason: `doseq` destructures it happily, so
    ;; [[:gap-px s1] [:gap-px s2]] registered and collided identically.
    ;; Rejecting both shapes leaves ONE way to collide — two producers — which
    ;; is exactly what the message says.
    ;; ABSENT is fine — most producers have no knobs. Present-but-not-a-map
    ;; is not.
    (when-not (or (nil? (:thresholds p)) (map? (:thresholds p)))
      (producer-error p (str ":thresholds must be a map, got "
                             (pr-str (type (:thresholds p))))))
    (doseq [[k spec] (:thresholds p)]
      (when-not (simple-keyword? k)
        (producer-error p (str "threshold key " (pr-str k)
                               " must be an UNQUALIFIED keyword — the consumer "
                               "key is built from its name, so a namespace "
                               "would be silently discarded")))
      (when-not (and (map? spec) (ifn? (:pred spec)) (contains? spec :default))
        (producer-error p (str "threshold " k
                               " needs {:pred fn :default v :doc s}")))
      ;; The DEFAULT must satisfy the producer's OWN :pred. It was exempt,
      ;; so a rule could ship a default its own predicate rejects — and
      ;; since the default applies precisely when the consumer supplies
      ;; nothing, the unchecked value is the one most runs actually use.
      (when-not (try ((:pred spec) (:default spec)) (catch Throwable _ false))
        (producer-error p (str "threshold " k "'s :default "
                               (pr-str (:default spec))
                               " fails the producer's own :pred — the default "
                               "is what runs when the consumer supplies "
                               "nothing, so it cannot be the one value "
                               "exempt from validation")))))
  (let [ids (map :id producers)]
    (when-let [dupes (seq (for [[id n] (frequencies ids) :when (> n 1)] id))]
      (throw (ex-info (str "duplicate producer ids " (vec dupes)
                           " — a finding's :invariant could not be traced "
                           "back to one rule")
                      {:ids (vec ids)}))))
  producers)

(defn threshold-key
  "The consumer-facing key for producer `id`'s threshold `k`. The producer
   id is used WHOLE — a namespaced id keeps its namespace, flattened into
   the key's namespace segment — because discarding it would collapse
   :acme/overlap and :bob/overlap onto one key. That collision is not
   merely confusing: `into` keeps the last spec, so one producer's
   declared :pred would stop being evaluated and a supplied value could
   relax a gate past its own declaration — the exact failure the
   unknown-key refusal exists to prevent."
  [id k]
  (keyword (if-let [ns- (namespace id)] (str ns- "." (name id)) (name id))
           (name k)))

(defn- threshold-index
  "{consumer-key -> [producer-id plain-key spec]} over every producer's
   declared thresholds. A producer's :gap-px is supplied by the consumer
   as :overlap/gap-px, so two rules may each own a :gap-px without
   colliding.

   The encoding CANNOT be proved injective by inspection — flattening a
   namespace into a dotted segment means :acme/overlap and :acme.overlap
   both yield :acme.overlap/gap-px — so this checks the RESULT instead of
   trusting the scheme. Any encoding that collides is caught here, at
   registration, rather than silently letting `into` keep one spec and
   drop another producer's :pred."
  [producers]
  (let [entries (for [p producers [k spec] (:thresholds p)]
                  [(threshold-key (:id p) k) [(:id p) k spec]])
        dupes (for [[consumer-key group] (group-by first entries)
                    :when (> (count group) 1)]
                [consumer-key (mapv (comp first second) group)])]
    (when (seq dupes)
      (throw (ex-info (str "two producers map to the SAME threshold key: "
                           (pr-str (into {} dupes))
                           " — one producer's declared :pred would stop being "
                           "evaluated and a supplied value could relax a gate "
                           "past its own declaration. Rename one producer id.")
                      {:collisions (into {} dupes)})))
    (into {} entries)))

(defn resolve-thresholds
  "Split consumer-supplied thresholds across producers, applying each
   declared default. Returns {producer-id {plain-key value}}. An unknown
   key throws — silently ignoring it would leave the consumer believing a
   stricter gate was armed than the one that ran."
  [producers supplied]
  (let [idx (threshold-index producers)]
    (doseq [[k v] supplied]
      (let [[_ _ spec] (or (get idx k)
                           (throw (ex-info
                                   (str "unknown threshold " k
                                        " — declared: " (vec (sort (keys idx))))
                                   {:threshold k :known (vec (sort (keys idx)))})))]
        (when-not ((:pred spec) v)
          (throw (ex-info (str "threshold " k " rejected value " (pr-str v)
                               (when-let [d (:doc spec)] (str " — " d)))
                          {:threshold k :value v})))))
    (into {}
          (for [p producers]
            [(:id p) (into {}
                           (for [[k spec] (:thresholds p)]
                             [k (get supplied
                                     (threshold-key (:id p) k)
                                     (:default spec))]))]))))

(defn- check-requires!
  "Every :requires key must have been SUPPLIED by the caller. Supplied-
   but-empty is a claim ('no proxy surfaces on this card'); absent is an
   oversight, and a rule that quietly returns [] for an input it never
   received is indistinguishable from a rule that found nothing.

   A key supplied as nil counts as ABSENT — nil is how a missing value
   spells itself, so accepting it would reopen the silence through the
   front door. `false` is NOT nil and stays a legitimate claim, which is
   why this tests nil rather than truthiness (:host-proxy? false is the
   ordinary case, not an omission)."
  [producer supplied-keys]
  (when-let [missing (seq (remove supplied-keys (:requires producer)))]
    (throw (ex-info (str "producer " (:id producer) " requires context "
                         (vec (sort missing))
                         ", which the caller did not supply — pass the key "
                         "explicitly (an empty value is a claim; an absent "
                         "one is an oversight)")
                    {:producer (:id producer) :missing (vec (sort missing))}))))

(defn- check-findings!
  "A producer must return a seq of finding maps carrying :card and
   :invariant — the two keys every downstream lane (exemptions, the
   verdict, the CI report) reads."
  [producer findings]
  (when-not (sequential? findings)
    (throw (ex-info (str "producer " (:id producer) " returned "
                         (pr-str (type findings)) ", not a seq of findings")
                    {:producer (:id producer)})))
  (doseq [f findings]
    (when-not (and (map? f) (contains? f :card) (contains? f :invariant))
      (throw (ex-info (str "producer " (:id producer)
                           " emitted a finding without :card/:invariant")
                      {:producer (:id producer) :finding f}))))
  findings)

(def builtin-producers
  "The two lanes protogen's own corpus runs, expressed as producers.

   Both declare EVERY input they read, and neither defaults a missing one.
   An earlier shape had `:tree` require only `:tree` and fall back to
   `(or caps {:vis-px? false})` — measured, that silently deleted the whole
   :zero-visible-area class, because invariants gates it on (:vis-px? caps)
   and the lane already guards on (contains? node :vis_px). A false default
   there can only ever suppress REAL findings, never prevent spurious ones,
   which made it the precise byte-identical-to-clean silence this registry
   claims to refuse — shipped inside the reference producers. The same
   applies to :host-proxy?, whose absence silently drops the positive
   'a host_proxy card must emit exactly one proxy-report' contract."
  [{:id :tree
    :fn (fn [{:keys [card-id tree nodes caps]}]
          (invariants/tree-findings card-id tree caps nodes))
    :requires #{:tree :caps}}
   {:id :emission
    :fn (fn [{:keys [card-id emissions host-proxy?]}]
          (invariants/emission-findings card-id emissions host-proxy?))
    :requires #{:emissions :host-proxy?}}])

(def emission-by-mode-producer
  "The emission lane over a card rendered in SEVERAL modes:
   :emissions-by-mode is {mode -> captured-lanes} and every mode is judged.

   A separate producer rather than a second shape for :emissions, because a
   key that means two things depending on what it holds is the ambiguity
   this registry keeps refusing elsewhere. A caller judging one mode uses
   the :emission builtin; a caller judging several declares that it has
   several. The mode is carried into the finding so a report can say WHICH
   render emitted, which a merged lane cannot."
  {:id :emission-by-mode
   :fn (fn [{:keys [card-id emissions-by-mode host-proxy?]}]
         (into []
               (mapcat (fn [[mode emissions]]
                         (map #(assoc % :mode mode)
                              (invariants/emission-findings card-id emissions
                                                            host-proxy?))))
               (sort-by key emissions-by-mode)))
   :requires #{:emissions-by-mode :host-proxy?}})

(defn card-findings
  "The full judgment for one rendered card: every registered producer,
   exemptions applied. Returns {:live [..] :exempted [..]
   :stale-exemptions [..]}; the gate fails on any :live or
   :stale-exemptions entry.

   Opts are the context inputs above plus :producers (default
   `builtin-producers`), :thresholds (consumer-supplied, namespaced by
   producer id) and :exemptions. The annotated walk is computed ONCE and
   shared, so every producer agrees about ancestry, hidden subtrees and
   snapped carousel pages."
  [{:keys [card-id tree producers thresholds exemptions]
    :or {producers builtin-producers thresholds {} exemptions []}
    :as opts}]
  (validate-producers! producers)
  ;; Every finding is keyed to a card, every exemption matches on that key,
  ;; and the whole verdict is reported per card. An absent :card-id produced
  ;; findings keyed to nil that no exemption could ever match and no report
  ;; could attribute — a run that looks like it judged something.
  (when-not (and (some? card-id) (or (string? card-id) (keyword? card-id)))
    (throw (ex-info (str ":card-id must be a string or keyword, got "
                         (pr-str card-id)
                         " — findings are keyed to it and exemptions match on "
                         "it, so an absent one yields findings nothing can "
                         "attribute or exempt")
                    {:card-id card-id})))
  (when (contains? opts :nodes)
    (throw (ex-info (str ":nodes is DERIVED by the registry from :tree, not a "
                         "caller input — supplying it would let a producer "
                         "that requires :nodes run against a walk the "
                         "registry never made, and (with :tree absent) "
                         "against nil, returning [] instead of throwing")
                    {:card card-id})))
  (let [resolved (resolve-thresholds producers thresholds)
        nodes (when (map? tree) (invariants/annotate-tree tree))
        ;; nil counts as ABSENT — see check-requires!. :nodes joins the
        ;; supplied set only when a walk was actually produced, so a
        ;; producer requiring it can never be handed nil.
        supplied (cond-> (set (for [[k v] opts :when (some? v)] k))
                   (some? nodes) (conj :nodes))
        base (assoc opts :nodes nodes :card-id card-id)
        raw (into []
                  (mapcat (fn [p]
                            (check-requires! p supplied)
                            (map #(assoc % :producer (:id p))
                                 (check-findings!
                                  p
                                  ((:fn p) (assoc base :thresholds
                                                  (get resolved (:id p))))))))
                  producers)]
    (invariants/apply-exemptions raw exemptions)))

(defn describe-producers
  "One-line registry summary for a run header — which rules are armed."
  ^String [producers]
  (str (count producers) " producers: "
       ;; `str`, not `name` — `name` drops the namespace, so two genuinely
       ;; distinct rules printed identically in the one line whose job is to
       ;; say which rules are armed.
       (str/join ", " (map (comp str :id) producers))))
