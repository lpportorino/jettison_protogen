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
     :framebuffer  the card's RAW rendered pixels, as ONE self-describing
                   value {:bytes byte[] :width n :height n}, checked at this
                   seam so that `supplied` means `usable`
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
            [devcards.invariants :as invariants]
            [devcards.outcome :as outcome]))

(set! *warn-on-reflection* true)

(def ^:private producer-keys
  "Every key a producer may declare. CLOSED, and closed the same way
   `context-keys` and the exemption key set are: an entry carrying a knob
   nothing reads looks armed and does nothing.

   :outcomes / :test-mode / :reasons are the ACT/EARL axes, all three
   OPTIONAL — a producer that declares none is two-way
   (`outcome/legacy-outcomes`) and deterministic, which is what every
   producer written before them already was.

   Widening this set from four keys to seven was FREE, because it had no
   canary of its own — the one state in which widening a closed set costs
   nothing is the state in which nothing pins it. `:severity` is the
   plausible next widening (it is the axis this repo deliberately does not
   have) and is what the canary uses."
  #{:id :fn :requires :thresholds :outcomes :test-mode :reasons})

(def ^:private act-declaration-keys
  "The producer keys that OPT IN to the ACT axes. A producer naming none of
   them keeps the open-map finding contract it was written against — see
   `outcome/reserved-plain-keys` and the ns docstring there."
  #{:outcomes :test-mode :reasons})

(defn- declares-act-axes?
  [producer]
  (boolean (some #(contains? producer %) act-declaration-keys)))

(def ^:private context-keys
  "Every ctx input a producer may declare in :requires. Closed, so a
   typo'd requirement fails at registration rather than at judgment.

   :expect and :emissions-by-mode exist because protogen's own gate needs
   them, but neither is protogen-specific: a corpus that declares what a
   card is FOR (a probe that must exhibit a defect judges inverted), and a
   corpus rendered in several MODES whose emissions must each be judged,
   are both ordinary shapes. Keeping them here rather than opening the set
   to arbitrary keys preserves the property the set exists for — a typo'd
   :requires fails at registration instead of silently never matching.

   :framebuffer IS ONE KEY, not the three that a byte array plus its
   dimensions naturally wants, and that is the whole reason a PIXEL rule can
   be admitted here without reopening the silence. `check-requires!` refuses
   an ABSENT key; with three parallel keys a producer that declares two of
   them and forgets the third is handed nil for the one it forgot, reads a
   nil width, and measures nothing. Bundled, the value is validated ONCE, at
   the seam, by `framebuffer-problem` — which is also the only way to close
   the half-supplied case the closed set cannot see by itself: {:width 800
   :height 480} is `some?`, satisfies the requirement, and hands a pixel rule
   nil bytes through the front door the nil-is-absent rule was written to
   shut.

   IT IS AN OBSERVATION, NOT A DECLARATION, so a rule's per-rule INTENT does
   NOT belong here beside it — the contours a border rule judges, the regions
   a contrast rule judges, the roles a layout rule judges are all consumer
   intent and ride `:declaration`'s own per-topic sub-keys, the way
   `devcards.layers` reads `(:layers declaration)`. One context key per rule
   is how a closed set stops being one."
  #{:tree :nodes :emissions :emissions-by-mode :host-proxy? :caps :classes
    :declaration :proxy-rects :expect :framebuffer :family})

;; :family IS AN OBSERVATION, and it is the one that makes a per-render verdict
;; expressible. A runner that renders one card under several theme families has
;; several DOMs to judge, and a finding that cannot say WHICH render it came
;; from is unactionable — worse, a lane that judges only the first render
;; reports the others clean without looking, which is byte-identical to having
;; looked. It sits beside :framebuffer rather than under :declaration because
;; it is a fact about the render the caller performed, never an intent: nothing
;; about family 1 is a claim, it is which theme was loaded. A producer that
;; matches a consumer's per-family DECLARATION reads both — the observation
;; here, the intent from (:designed-flags declaration).

(def framebuffer-bytes-per-pixel
  "RGBA8888 — the ONE framebuffer layout this runner admits, which is what
   makes a buffer's expected byte length a DERIVABLE fact rather than a
   caller's claim. `devcards.host` gates it at boot (`controls_fb_format`
   must report 1, else the module is refused) and `read-framebuffer!` copies
   exactly (* w h 4) bytes out of linear memory.

   Deliberately NOT a caller-supplied :format field on the value. Nothing in
   this system can vary it today, so the field would be a knob that reads as
   a capability declaration and does nothing, and the branch reading it could
   never fire — the objection `producer-keys` makes about widening itself,
   and the one `outcome/validate-policy!` makes about naming an unreachable
   value. A renderer that grows a second layout adds the field in the SAME
   diff as the branch that reads it."
  4)

(defn framebuffer-problem
  "nil when `fb` is a usable `:framebuffer` context value, else a one-line
   problem string. The value is {:bytes byte[] :width n :height n}.

   THIS RUNS AT THE SEAM because `check-requires!` cannot. Its
   absent-is-an-oversight rule reaches the top-level key and stops there:
   {:width 800 :height 480} is `some?`, satisfies a producer's :requires, and
   hands a pixel rule nil bytes — the silence the nil rule was written to
   shut, arriving one level down. Classifying at the seam where the value is
   acquired is also what keeps the NEXT pixel rule from re-deriving this
   check slightly differently; a rule may refuse a framebuffer for its own
   reasons, but it may not be handed an unusable one.

   The LENGTH clause is the one that is easy to leave out and is the reason
   the dimensions are evidence rather than decoration: a rule sampling [x y]
   through a width that disagrees with the buffer reads the wrong pixels, and
   every verdict it then returns is well-formed and wrong. There is no
   partial credit here — a mismatch means the two halves of the value
   describe different images, and neither is identifiable as the correct
   one."
  [fb]
  (if-not (map? fb)
    (str "must be a map {:bytes byte[] :width n :height n}, got "
         (pr-str (type fb)))
    (let [px (:bytes fb)
          w (:width fb)
          h (:height fb)]
      (cond
        (not (bytes? px))
        (str ":bytes must be a byte array of RAW pixels, got "
             (pr-str (type px)))

        (not (pos-int? w))
        (str ":width must be a positive integer, got " (pr-str w))

        (not (pos-int? h))
        (str ":height must be a positive integer, got " (pr-str h))

        (not= (* (long w) (long h) (long framebuffer-bytes-per-pixel))
              (alength ^bytes px))
        (str ":bytes holds " (alength ^bytes px) " bytes, but :width " w
             " x :height " h " x " framebuffer-bytes-per-pixel
             " needs "
             (* (long w) (long h) (long framebuffer-bytes-per-pixel))
             " — the dimensions and the buffer describe different images, "
             "and nothing here can say which one is right")

        :else nil))))

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
                               "exempt from validation"))))
    ;; The ACT/EARL declaration. ABSENT is legal and means #{:failed}, so
    ;; every producer that predates this registers unchanged.
    ;;
    ;; This clause is what makes "neither lane may borrow the other's shape"
    ;; a REGISTRATION-TIME property rather than prose. A geometry rule
    ;; declares no :outcomes and is therefore structurally two-way: a future
    ;; author cannot soften one of its findings to :cantTell in a
    ;; four-character diff, because it throws, naming the producer. That
    ;; softening is this repo's own regression class — a defaulted :caps and
    ;; a defaulted classification each deleted a whole finding class in
    ;; silence — in the one form a bare vocabulary cannot see.
    (let [outs (:outcomes p outcome/legacy-outcomes)]
      (when-not (and (set? outs) (seq outs) (every? outcome/outcomes outs))
        (producer-error p (str ":outcomes must be a non-empty subset of "
                               (vec (sort outcome/outcomes)) ", got "
                               (pr-str outs))))
      ;; :passed is refused as a DECLARATION, and refused again on the
      ;; finding itself in `outcome/axis-problem`. Two layers because two
      ;; populations: this one cannot see the corpus gates or the interaction
      ;; lane, which never meet the registry. See
      ;; `outcome/unreportable-outcomes` for why it is a category error and
      ;; why it was the cheapest laundering path in the design.
      (when-let [bad (seq (filter outcome/unreportable-outcomes outs))]
        (producer-error p (str ":outcomes names " (vec (sort bad))
                               ", which a FINDING may never carry — a "
                               "reported finding is by construction not a "
                               "pass, and this vector has no per-target "
                               "result model for one to mean anything else")))
      (when-not (contains? outcome/test-modes
                           (:test-mode p outcome/default-test-mode))
        (producer-error p (str ":test-mode must be one of "
                               (vec (sort outcome/test-modes)) ", got "
                               (pr-str (:test-mode p)))))
      ;; A CLOSED reason vocabulary, mandatory the moment a producer claims it
      ;; can emit any outcome that owes one — :cantTell, :inapplicable or
      ;; :untested (`outcome/reasoned-outcomes`). The CONCEPT is axe-core's
      ;; declared "incomplete" keys; its IMPLEMENTATION is refused — axe
      ;; validates only that the key is a string, so a typo is a new reason. A
      ;; bare set is not enough either: a reason with no stated meaning names
      ;; the gap without saying what would close it, so the vocabulary is a
      ;; map to non-blank docs.
      (if-let [reasoned (seq (filter outcome/reasoned-outcomes outs))]
        (do
          (when-not (and (map? (:reasons p)) (seq (:reasons p)))
            (producer-error p (str "declares " (vec (sort reasoned))
                                   " but no non-empty :reasons map — an "
                                   "unenumerated reason vocabulary is an open "
                                   "one, and an open one cannot be reviewed")))
          (doseq [[k doc] (:reasons p)]
            (when-not (simple-keyword? k)
              (producer-error p (str "reason key " (pr-str k)
                                     " must be an UNQUALIFIED keyword — the "
                                     "producer id names it already")))
            (when-not (and (string? doc) (not (str/blank? doc)))
              (producer-error p (str "reason " k " needs a non-blank doc")))))
        (when (contains? p :reasons)
          (producer-error p (str ":reasons but :outcomes names none of "
                                 (vec (sort outcome/reasoned-outcomes))))))))
  (let [ids (map :id producers)]
    (when-let [dupes (seq (for [[id n] (frequencies ids) :when (> n 1)] id))]
      (throw (ex-info (str "duplicate producer ids " (vec dupes)
                           " — a finding's :invariant could not be traced "
                           "back to one rule")
                      {:ids (vec ids)}))))
  producers)

(defn validate-exemption-reasons!
  "Every exemption's `:act/reason` must be one some ARMED producer declares.
   `armed` is the producer set armed for the RUN — the union across every
   lane — never one lane's vector. Throws naming the exemption and the
   declared vocabulary; returns the exemptions.

   WHY THE ARMED SET AND NOT THE CALLER'S. `card-findings` runs once per card
   PER LANE with a lane-scoped producer vector, while exemption lists are
   GLOBAL — which is exactly what `apply-exemptions`' stale ratchet assumes.
   Deriving the vocabulary from the call's own vector therefore threw on
   every lane whose producers happened not to include the declaring one, and
   the message blamed the EXEMPTION: the wrong place to look, for an entry
   that was globally correct. Measured before this existed: a `contrast`
   producer declaring `:reasons {:noise-band …}` and one global exemption
   naming it returned normally under `:producers [contrast]` and threw
   `no armed producer declares it. Declared: []` under `:producers [tree]`.

   WHY NOT RELAX IT INSTEAD. An exemption naming an undeclared reason matches
   nothing, and the stale-exemption ratchet that would catch that is computed
   by `card-findings` but dropped by every lane in this repo — so relaxing
   the throw trades a wrong error for NO error. Same class as the unknown
   threshold key: the closed set must not leak out through the exemption
   door."
  [armed exemptions]
  (let [vocab (into #{} (mapcat (comp keys :reasons)) armed)]
    (doseq [e exemptions :when (and (map? e) (contains? e :act/reason))]
      (when-not (contains? vocab (:act/reason e))
        (throw (ex-info (str "exemption names :act/reason "
                             (pr-str (:act/reason e))
                             " — no ARMED producer declares it. Declared "
                             "across the armed set: " (vec (sort vocab)))
                        {:exemption e :known (vec (sort vocab))})))))
  exemptions)

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

(defn- check-outcome!
  "The ACT/EARL axes on ONE finding, checked against the DECLARING producer.
   An absent :act/outcome is `outcome/default-outcome`, so a rule written
   before this existed is unaffected — and so is a rule that uses the
   unnamespaced words for its own payload, which the open finding map has
   always permitted (see `outcome/reserved-plain-keys`).

   The mode axis is the PRODUCER's and is stamped by the registry, never
   carried on a finding: a lane cannot claim reproducibility one finding at a
   time, which is precisely what the axis exists to make impossible.

   There is deliberately NO 'not in the ACT vocabulary' clause here. It was
   unreachable: `validate-producers!` has already proved the declared set is
   a subset of the vocabulary, so `(contains? declared o)` implies it, and
   deleting the clause left the suite green because both of its own
   assertions threw from neighbouring clauses. The vocabulary is checked
   where it can actually be violated — at registration, and at the verdict
   seam for the populations that never register."
  [producer f]
  (let [o (outcome/finding-outcome f)
        declared (:outcomes producer outcome/legacy-outcomes)
        reasons (:reasons producer {})]
    ;; The near-miss refusal, scoped to producers that OPTED IN. For every
    ;; other producer the plain words stay opaque payload, which is the
    ;; entire backward-compatibility seam: no producer that exists today
    ;; declares an ACT axis, so this reaches nobody by construction.
    (when (declares-act-axes? producer)
      (when-let [k (some #(when (contains? f %) %)
                         (sort (keys outcome/reserved-plain-keys)))]
        (throw (ex-info (str "producer " (:id producer) " declares ACT axes "
                             "and emitted " k " — the finding axis is "
                             (get outcome/reserved-plain-keys k)
                             "; the unnamespaced name is payload and would "
                             "be read as " (pr-str outcome/default-outcome))
                        {:producer (:id producer) :finding f}))))
    (when-not (contains? declared o)
      (throw (ex-info (str "producer " (:id producer) " emitted " (pr-str o)
                           " but declares only " (vec (sort declared))
                           " — widen :outcomes deliberately, in the same "
                           "diff, or fix the rule")
                      {:producer (:id producer) :outcome o :finding f})))
    (when (contains? f :act/test-mode)
      (throw (ex-info (str "producer " (:id producer) " put :act/test-mode on "
                           "a finding — the mode is the PRODUCER's "
                           "declaration and the registry stamps it")
                      {:producer (:id producer) :finding f})))
    (if (contains? outcome/reasoned-outcomes o)
      (when-not (contains? reasons (:act/reason f))
        (throw (ex-info (str "producer " (:id producer) " emitted a "
                             (pr-str o) " with :act/reason "
                             (pr-str (:act/reason f))
                             " — its declared reasons are "
                             (vec (sort (keys reasons)))
                             ". 'the score is in the noise band', 'there is "
                             "no glyph ink here' and 'no mask was supplied' "
                             "are three different non-answers; one that will "
                             "not say which it is has re-fused them")
                        {:producer (:id producer) :finding f})))
      (when (contains? f :act/reason)
        (throw (ex-info (str "producer " (:id producer) " attached an "
                             ":act/reason to a " (pr-str o) " finding — the "
                             "reason vocabulary belongs to "
                             (vec (sort outcome/reasoned-outcomes))
                             " and nothing else")
                        {:producer (:id producer) :finding f}))))))

(defn- check-findings!
  "A producer must return a seq of finding maps carrying :card and
   :invariant — the two keys every downstream lane (exemptions, the
   verdict, the CI report) reads — and, where it names one, an ACT/EARL
   outcome its own declaration admits."
  [producer findings]
  (when-not (sequential? findings)
    (throw (ex-info (str "producer " (:id producer) " returned "
                         (pr-str (type findings)) ", not a seq of findings")
                    {:producer (:id producer)})))
  (doseq [f findings]
    (when-not (and (map? f) (contains? f :card) (contains? f :invariant))
      (throw (ex-info (str "producer " (:id producer)
                           " emitted a finding without :card/:invariant")
                      {:producer (:id producer) :finding f})))
    (check-outcome! producer f))
  findings)

(def builtin-producers
  "The registry's DEFAULT producer menu — the DOM and emission lanes in their
   plain, single-mode form. NOT protogen's armed set: its gate names its own
   producers in `devcards.lanes` (`tree-producer` for atomic cards, the
   `:tree` builtin plus `emission-by-mode-producer` for compositions), so the
   `:emission` entry below is run by no DEVCARDS lane in this repo (`scratchcard.lanes` arms it).

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

(defn builtin-producer
  "The builtin producer with `id`, or a throw naming what is available.

   Exists so no caller selects one by POSITION. `(first
   builtin-producers)` couples the caller to a vector order that nothing
   declares and no test pins, so appending or reordering an entry
   silently repoints that lane at a different rule — a lane still runs,
   still returns findings, and is simply no longer the rule the caller
   meant. A lookup cannot fail that way: an id that is not there throws
   instead of resolving to whatever sits at that index."
  [id]
  (or (first (filter #(= id (:id %)) builtin-producers))
      (throw (ex-info (str "no builtin producer " id " — have "
                           (mapv :id builtin-producers))
                      {:id id :available (mapv :id builtin-producers)}))))

(def emission-by-mode-producer
  "The emission lane over a card rendered in SEVERAL modes:
   :emissions-by-mode is {mode -> captured-lanes} and every mode is judged.

   A separate producer rather than a second shape for :emissions, because a
   key that means two things depending on what it holds is the ambiguity
   this registry keeps refusing elsewhere. A caller judging one mode uses
   the :emission builtin; a caller judging several declares that it has
   several. The mode is carried into the finding so a report can say WHICH
   render emitted, which a merged lane cannot.

   REFUSES AN EMPTY MAP, for the same reason `validate-producers!` refuses
   an empty producer set: `(sort-by key {})` is empty, the mapcat yields
   [], and `check-requires!` is satisfied because {} is `some?` — so a card
   would pass having run not one contract, and with :host-proxy? true the
   POSITIVE 'must emit exactly one proxy-report' arm would never fire. That
   output is byte-identical to a clean judgement, which is the precise
   silence this registry exists to refuse. Supplied-but-empty is a CLAIM
   here as everywhere; the claim is just one no caller can truthfully make."
  {:id :emission-by-mode
   :fn (fn [{:keys [card-id emissions-by-mode host-proxy?]}]
         (when (empty? emissions-by-mode)
           (throw (ex-info (str "refusing an EMPTY :emissions-by-mode for card "
                                (pr-str card-id) " — judging zero modes passes "
                                "the card without running a single contract")
                           {:producer :emission-by-mode :card card-id})))
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
   :stale-exemptions [..]}. A gate fails on any :live entry;
   :stale-exemptions is COMPUTED here but the CALLER must fail on it itself —
   protogen's own lanes take :live only and supply no exemptions, so nothing
   in this repo arms that ratchet yet.

   Opts are the context inputs above plus :producers (default
   `builtin-producers`), :thresholds (consumer-supplied, namespaced by
   producer id), :exemptions, and :armed-producers — the run's whole armed
   set, required exactly when an exemption names an `:act/reason`, because
   the reason vocabulary is a property of the RUN and this call sees one
   LANE. See `validate-exemption-reasons!`. The annotated walk is computed
   ONCE and shared, so every producer agrees about ancestry, hidden subtrees
   and snapped carousel pages."
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
  ;; A SUPPLIED framebuffer must be a USABLE one. nil is already ABSENT
  ;; (`check-requires!`), so this clause is only about the half-supplied
  ;; value that rule cannot see inside — see `framebuffer-problem`. The
  ;; framebuffer itself is kept OUT of the ex-data: it is megabytes of raw
  ;; pixels, and an error report that dumps them is one nobody reads.
  (when (some? (:framebuffer opts))
    (when-let [problem (framebuffer-problem (:framebuffer opts))]
      (throw (ex-info (str ":framebuffer was supplied but is not usable — it "
                           problem ". A pixel rule handed this samples the "
                           "wrong bytes and still returns a verdict")
                      {:card card-id :framebuffer-problem problem}))))
  ;; THE REASON VOCABULARY IS THE ARMED SET'S, NEVER THIS CALL'S. `producers`
  ;; here is one LANE's vector — this fn runs once per card per lane — while
  ;; exemption lists are GLOBAL, so reading the vocabulary off `producers`
  ;; made a globally-correct exemption throw on every lane that happens not to
  ;; arm the declaring producer. The caller therefore DECLARES the armed set,
  ;; and an absent declaration is refused rather than defaulted back to
  ;; `producers`: that default is the defect, and a default that reintroduces
  ;; the bug it replaced is worse than none. Absent-is-an-oversight is the
  ;; same rule `check-requires!` applies one seam over.
  ;;
  ;; Scoped to exemptions that actually NAME a reason, so every caller written
  ;; before the axis existed is unaffected — nothing in this repo declares
  ;; :reasons today, so this reaches nobody by construction.
  (when (some #(and (map? %) (contains? % :act/reason)) exemptions)
    (when-not (contains? opts :armed-producers)
      (throw (ex-info (str "an exemption names an :act/reason, so the run's "
                           "ARMED producer set must be supplied as "
                           ":armed-producers — the vocabulary is the union "
                           "across every lane, and this call's :producers is "
                           "only one of them (see "
                           "`validate-exemption-reasons!`)")
                      {:card card-id})))
    (validate-exemption-reasons! (:armed-producers opts) exemptions))
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
                            ;; :act/test-mode is stamped only when it CARRIES
                            ;; information. Every producer here and in every
                            ;; consumer is :automatic today, so the persisted
                            ;; findings vector stays byte-identical; the key
                            ;; appears exactly when a lane is not reproducible.
                            (let [mode (:test-mode p outcome/default-test-mode)]
                              (map #(cond-> (assoc % :producer (:id p))
                                      (not= outcome/default-test-mode mode)
                                      (assoc :act/test-mode mode))
                                   (check-findings!
                                    p
                                    ((:fn p) (assoc base :thresholds
                                                    (get resolved (:id p)))))))))
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
