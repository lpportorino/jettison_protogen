(ns readback-strings
  "The STIMULUS INSTRUMENT for `docs/VLM-READBACK-PROTOCOL.md`: high-entropy
   read-back strings, their exact entropy, the pre-registered reply parser, and
   the scorer that grades a recovered string against the one that was rendered.

   GATES NOTHING, AND CANNOT. Nothing here renders, judges a card, or produces a
   verdict a battery reads. It manufactures a labelled set once;
   `docs/UI-QUALITY-CONTRACTS.md` §0 is where a deterministic producer earns a
   verdict shape, and this is not one. The only non-zero exit is an instrument
   refusing to emit a corpus it can prove malformed — never a quality finding
   about a screen.

   WHY RANDOM STRINGS AND NOT WORDS. Render `Speed 42` and a reader reconstructs
   it from context; a correct read then measures the prior, not the pixels. Every
   string here is drawn from a declared alphabet with a declared, exactly
   computable entropy, so a correct read is evidence about the glyphs and about
   nothing else. `entropy-bits` is that number and `gen-corpus` REFUSES a
   configuration below `min-entropy-bits` rather than warn — a weak corpus
   silently converts the whole campaign into a guessing measurement.

   THE STRING IS KEYED ON THE STIMULUS, NOT ON THE CELL, and that distinction is
   the one an earlier draft of this namespace got wrong. A degradation LADDER
   sweeps a level while holding content fixed; if every rung drew a fresh string,
   the level-to-level difference would carry string-difficulty variance that
   NOTHING in the protocol estimates — the replicate axis holds the string fixed
   by definition, so it cannot see it either. A non-monotonic ladder would then
   look like a render defect while being a property of the draw.
   `stimulus-id` (content, draw) seeds the string; `cell-id` (content, level,
   draw) addresses the rendered cell. Three variance axes, cleanly separated:
   LEVEL is the sweep, DRAW replicates the string, RUN replicates nothing.

   THE ONE-DIRECTIONAL CLAIM LIVES IN THE DOCUMENT, NOT HERE. This namespace
   deliberately holds no notion of `readable`. `pass-message` is the ONE
   sanctioned sentence a positive result may carry, and `banned-claim-tokens` is
   the vocabulary it may never contain. Both are data, so the test can hold the
   instrument to them instead of a reviewer having to notice.

   THE THREE POOLS, and the split is not cosmetic:

     `confusion-classes`     the SCORING vocabulary. Equivalence classes over
                             every character a READER might produce, including
                             ones this generator never emits (`o`, `i`, `s`,
                             `b`, `z`). Widening a class re-scores an existing
                             campaign without re-rendering it — which is the
                             whole reason it is data.
     `emitted-confusables`   the subset a STRING may contain: the cockpit set
                             0/O, 1/l/I, 5/S, 8/B, 2/Z. Every generated string
                             carries EXACTLY `:confusables` of them, so the
                             entropy is a closed form rather than an estimate.
     `singleton-chars`       everything else in the alphabet.

   THE SINGLETON POOL IS A JUDGEMENT, NOT A MEASUREMENT, and saying so is the
   only honest description of it. One rule is applied: NO SINGLETON MAY CROWD A
   MEMBER OF A DECLARED CLASS. That is what removes `3` (crowds `8`/`B`), `6`
   (crowds `b`), `7` (crowds `1`), `L` (crowds `l`/`I`/`1`), `D` and `Q` (crowd
   `0`/`O`), `G` (crowds `6` and `O`), and every lower/upper pair differing only
   in SIZE (`c`/`C`, `v`/`V`, `x`/`X`), which would otherwise smuggle in a
   confusion nobody declared. `l` is the only lowercase character in the alphabet
   and it is there on purpose, as a member of the 1/l/I class.

   WHAT THE RULE DOES NOT COVER, stated rather than hidden: SINGLETON-TO-SINGLETON
   confusion. `C` against `U`, `U` against `V`, `M` against `N`, `E` against `F`
   are all still in the pool, and whether any of them collide at 12px in this
   face is not something this namespace knows. Nor is the rule's own application
   a measurement — it is a reading of glyph shapes. `docs/VLM-READBACK-PROTOCOL.md`
   §4.4 names the instrument that would replace the judgement with arithmetic
   (rasterise each pair offline and compare bitmaps), and IT HAS NOT BEEN RUN.

   EVERY UNDECLARED CONFUSION SCORES AS AN ORDINARY SUBSTITUTION, so confusion is
   UNDER-reported — the direction that flatters the font. Do not read a low
   `:confusable-substitutions` as evidence that disambiguation is working.

   VALIDATION IS A FUNCTION, NEVER A LOAD-TIME ASSERT. `alphabet-problems`
   returns findings; nothing throws while the namespace loads. Deliberate: a
   canary that breaks the load reds its file having executed no assertion, and a
   red carrying no information is not evidence. Its 3-arity takes the pools, so
   every clause can be shown FIRING rather than only shown quiet.

   DETERMINISM IS ACROSS JVMs, not merely across runs in one process. Every draw
   goes through `java.util.Random.nextInt(int)`, whose algorithm the javadoc
   specifies exactly, seeded from SHA-256 over the master seed and the stimulus
   id. `clojure.core/shuffle` and `rand-int` are unusable here — both reach for a
   process-wide generator this namespace must never touch.

   Run it:

     tools/uber.sh 'cd tools/devcards && clojure -M:readback-strings --seed S'"
  (:require [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.math BigInteger)
           (java.security MessageDigest)
           (java.util Random)))

;; ── Protocol identity ───────────────────────────────────────────────────────
;; A labelled set is valid only for the (renderer build x font set x model x
;; instrument) that produced it. This constant is the instrument half; the other
;; three are the campaign's (`required-provenance-keys`). BUMP IT whenever the
;; alphabet, the classes, the draw order, the seeding or the scorer changes — a
;; corpus whose version differs from the code reading it is stale, and a stale
;; label is worse than a missing one.
;;
;; 1 -> 2: the singleton pool lost `3 6 7 L` under the crowds-a-declared-class
;;         rule, and the string moved from the cell id to the STIMULUS id. Both
;;         re-mint every string in every corpus.
(def ^:const protocol-version 2)

;; ── The scoring vocabulary ──────────────────────────────────────────────────
;; Equivalence classes over characters a READER might produce. A substitution
;; whose expected and observed characters share a class is CONFUSION-ATTRIBUTED;
;; anything else is an ordinary substitution. Classes must be pairwise disjoint
;; and hold at least two members each — `alphabet-problems` checks both.
(def confusion-classes
  [#{\0 \O \o}
   #{\1 \l \I \i}
   #{\5 \S \s}
   #{\8 \B \b}
   #{\2 \Z \z}])

;; The subset a generated string may contain. Order is fixed because it indexes
;; the uniform draw; reordering it re-mints every string in every corpus.
(def emitted-confusables
  [\0 \O \1 \l \I \5 \S \8 \B \2 \Z])

;; Everything else in the alphabet. See the ns docstring for the ONE rule that
;; produced this list, and for what that rule does not cover.
(def singleton-chars
  [\4 \9
   \A \C \E \F \H \J \K \M \N \P \R \T \U \V \W \X \Y])

(def alphabet (vec (concat emitted-confusables singleton-chars)))

;; The shared ASCII BASE range every compiled font in this tree carries.
;; `tools/gen_fonts.sh` gives each family its own `--range` — B612 Mono adds
;; U+00B1 and U+2192, Orbitron does not have them — but `0x20-0x7E` is the base
;; both share at every size. A character outside it has no glyph, LVGL draws
;; nothing, and the ground truth becomes unrecoverable by ANY reader: a cell
;; voided silently.
(def ^:const glyph-range-lo 0x20)
(def ^:const glyph-range-hi 0x7E)

(def ^:private char->class-id
  (into {} (for [[i cls] (map-indexed vector confusion-classes)
                 ch cls]
             [ch i])))

(defn same-class?
  "True when two characters are members of the SAME declared confusion class.
   False for any character outside every class — including one the generator
   never emits. That is the under-counting direction the ns docstring names, and
   it is preferred to inventing a class at scoring time."
  [a b]
  (let [ca (char->class-id a)]
    (boolean (and ca (= ca (char->class-id b))))))

(defn alphabet-problems
  "Findings against the closure the three pools owe each other. Empty means the
   EIGHT clauses below hold — not that the pools are good, which is a judgement
   no arithmetic here makes (see the ns docstring).

   The 3-arity exists so every clause can be shown FIRING. The pools are
   load-time `def`s, so a `with-redefs` on them cannot move the derived
   `alphabet`; passing them in can."
  ([] (alphabet-problems confusion-classes emitted-confusables singleton-chars))
  ([classes emitted-conf singles]
   ;; `classed` is derived from the classes ARGUMENT rather than read off
   ;; `char->class-id`, which is built once at load: a check that consults a
   ;; cached view of the thing it is checking cannot see the pools it was handed.
   (let [emitted-set (set emitted-conf)
         singles-set (set singles)
         classed (into #{} cat classes)
         alpha (vec (concat emitted-conf singles))]
     (vec
      (concat
       (when (empty? emitted-conf)
         [{:clause :pool-is-empty :detail :emitted-confusables}])
       (when (empty? singles)
         ;; Not pedantry: with an empty pool `gen-string` reaches
         ;; `(.nextInt r 0)` and dies with "bound must be positive", which is a
         ;; crash rather than a finding and names nothing.
         [{:clause :pool-is-empty :detail :singleton-chars}])
       (for [[i a] (map-indexed vector classes)
             [j b] (map-indexed vector classes)
             :when (and (< i j) (seq (set/intersection a b)))]
         {:clause :classes-disjoint :detail [i j (set/intersection a b)]})
       (for [[i cls] (map-indexed vector classes)
             :when (< (count cls) 2)]
         {:clause :class-needs-two-members :detail [i cls]})
       (for [ch emitted-conf
             :when (not (contains? classed ch))]
         {:clause :emitted-confusable-has-no-class :detail ch})
       (for [ch singles
             :when (contains? classed ch)]
         {:clause :singleton-is-classed :detail ch})
       (for [ch (set/intersection emitted-set singles-set)]
         {:clause :pools-overlap :detail ch})
       (for [ch alpha
             :when (not (<= glyph-range-lo (int ch) glyph-range-hi))]
         {:clause :outside-compiled-glyph-range :detail ch})
       (when (not= (count alpha) (count (set alpha)))
         [{:clause :alphabet-has-duplicates
           :detail (into {} (filter #(< 1 (val %)) (frequencies alpha)))}]))))))

;; ── Seeding ────────────────────────────────────────────────────────────────

(defn- sha256 ^bytes [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

(defn- bytes->seed ^long [^bytes b]
  (loop [i 0 acc 0]
    (if (< i 8)
      (recur (inc i) (bit-or (bit-shift-left acc 8) (long (bit-and (aget b i) 0xFF))))
      acc)))

(defn stimulus-seed
  "Per-stimulus seed = first 8 bytes of SHA-256(master NUL stimulus-id).

   The NUL separator is load-bearing: without it (\"ab\",\"c\") and (\"a\",\"bc\")
   seed identically and two distinct stimuli render the same string. Preimage
   resistance is the second reason — a leaked answer key does not surrender the
   master seed, so the remaining stimuli stay unguessable."
  ^long [^String master-seed ^String sid]
  (bytes->seed (sha256 (str master-seed "\u0000" sid))))

(defn- sample-positions
  "k distinct indices in [0,len), uniform, by partial Fisher-Yates. Uses only
   `Random.nextInt(int)`, whose algorithm the javadoc specifies exactly — so the
   same seed yields the same positions on every JVM."
  [^Random r len k]
  (loop [pool (vec (range len)) i 0 out []]
    (if (= i k)
      out
      (let [j (+ i (.nextInt r (int (- len i))))
            v (nth pool j)]
        (recur (assoc pool j (nth pool i) i v) (inc i) (conj out v))))))

(defn gen-string
  "The string for one STIMULUS: EXACTLY `confusables` characters drawn from
   `emitted-confusables`, at uniformly chosen positions, every other position
   drawn from `singleton-chars`.

   Uniform over {strings of `length` with exactly `confusables` emitted
   confusables}, which is what makes `entropy-bits` a closed form instead of an
   estimate. Any post-filter added here — a wordlist rejection, a no-triple-run
   rule — changes that set and MUST be reflected in `entropy-bits`, or the corpus
   starts claiming entropy it does not have."
  ^String [^String master-seed ^String sid {:keys [length confusables]}]
  (let [r (Random. (stimulus-seed master-seed sid))
        conf-at (set (sample-positions r length confusables))
        n-conf (count emitted-confusables)
        n-single (count singleton-chars)]
    (loop [i 0 acc []]
      (if (= i length)
        (str/join acc)
        (recur (inc i)
               (conj acc (if (contains? conf-at i)
                           (nth emitted-confusables (.nextInt r n-conf))
                           (nth singleton-chars (.nextInt r n-single)))))))))

;; ── Entropy ────────────────────────────────────────────────────────────────

(defn- binomial ^BigInteger [n k]
  (if (or (neg? k) (> k n))
    BigInteger/ZERO
    (loop [i 0 acc BigInteger/ONE]
      (if (= i k)
        acc
        (recur (inc i)
               (.divide (.multiply acc (BigInteger/valueOf (- n i)))
                        (BigInteger/valueOf (inc i))))))))

(defn space-size
  "The EXACT number of distinct strings `gen-string` can produce for a given
   (length, confusables): choose the confusable positions, fill them from the
   confusable pool, fill the rest from the singleton pool."
  ^BigInteger [length confusables]
  (.multiply (.multiply (binomial length confusables)
                        (.pow (BigInteger/valueOf (count emitted-confusables))
                              (int confusables)))
             (.pow (BigInteger/valueOf (count singleton-chars))
                   (int (- length confusables)))))

(defn- log2-big ^double [^BigInteger n]
  (if (<= (.signum n) 0)
    Double/NEGATIVE_INFINITY
    (let [bl (.bitLength n)
          shift (max 0 (- bl 52))
          head (.doubleValue (.shiftRight n shift))]
      (+ (double shift) (/ (Math/log head) (Math/log 2.0))))))

(defn entropy-bits
  "log2 of the exact space size. Goes through `BigInteger.bitLength` rather than
   a double factorial, so it stays accurate at any length instead of overflowing
   to Infinity."
  ^double [length confusables]
  (log2-big (space-size length confusables)))

;; The floor `gen-corpus` refuses to go below. Rationale, so it is a decision and
;; not a constant: at 40 bits the a-priori probability of naming one string blind
;; is under 1e-12, so across a campaign of even 10^4 cells the expected number of
;; lucky exact matches is under 1e-8. Below that the no-image control arm stops
;; being a formality and starts being the measurement.
(def ^:const min-entropy-bits 40.0)

;; ── The reply parser ───────────────────────────────────────────────────────

(def no-recovery-sentinel
  "The one token a reader may reply with instead of a string.

   IT NAMES THE OUTCOME, NOT THE RENDER, and an earlier draft of this namespace
   got that wrong. A sentinel spelled `UNREADABLE` asks the model for a verdict
   about the IMAGE and hands the campaign a token that reads like a negative
   label — inversion site 1 of `docs/VLM-READBACK-PROTOCOL.md` §2.1, invited by
   the instrument itself. This spelling says only what happened to this reader,
   and it scores as an ordinary MISS, which §7 maps to UNKNOWN."
  "NORECOVERY")

(defn parse-reply
  "Turn a raw model reply into an outcome. PURE, TOTAL, AND FIXED BEFORE THE
   CAMPAIGN — which is the whole point of its existing at all.

   `docs/VLM-READBACK-PROTOCOL.md` §1 names the parser as one of two judge-shaped
   hazards that survive the subject/judge distinction: something has to turn text
   into the string that gets scored, and any rule invented after the replies are
   in is a judgement made by someone who already knows the answer.

   THE RULE, in full, so it can be pre-registered by citation:
     - trim leading and trailing whitespace;
     - empty            -> :unparseable
     - the sentinel     -> :no-recovery
     - contains any interior whitespace -> :unparseable
     - anything else    -> :recovered, VERBATIM.

   VERBATIM matters. No case folding, no quote stripping, no restriction to the
   alphabet: a reply of `o` where `0` was rendered must reach the scorer as `o`,
   or the confusion it represents is silently repaired into a correct answer.

   STRICTNESS IS NOT FREE, and §1's \"a miss costs nothing\" was wrong to imply
   otherwise. A hedged-but-correct reply rejected here suppresses a POSITIVE —
   the only label a model can produce — so the cost is statistical power and a
   threshold estimate biased toward severity. What it buys is that no scoring
   decision is ever made by a human holding the answer key. That trade is
   declared, not free."
  [reply]
  (if (nil? reply)
    {:outcome :unparseable :value nil :raw nil}
    (let [t (str/trim (str reply))]
      (cond
        (str/blank? t) {:outcome :unparseable :value nil :raw reply}
        (= t no-recovery-sentinel) {:outcome :no-recovery :value nil :raw reply}
        (re-find #"\s" t) {:outcome :unparseable :value nil :raw reply}
        :else {:outcome :recovered :value t :raw reply}))))

;; ── Scoring ────────────────────────────────────────────────────────────────

(defn- lev-table
  "Full Levenshtein DP table as a vector of rows, `(inc m)` x `(inc n)`."
  [expected observed]
  (let [m (count expected) n (count observed)]
    (reduce
     (fn [rows i]
       (conj rows
             (reduce (fn [row j]
                       (conj row
                             (if (zero? j)
                               i
                               (let [cost (if (= (nth expected (dec i)) (nth observed (dec j))) 0 1)]
                                 (min (+ (nth (nth rows (dec i)) (dec j)) cost)
                                      (inc (nth (nth rows (dec i)) j))
                                      (inc (nth row (dec j))))))))
                     [] (range (inc n)))))
     [(vec (range (inc n)))]
     (range 1 (inc m)))))

(defn align
  "Levenshtein alignment of `expected` against `observed`, as an ordered vector
   of ops. Backtrace priority is diagonal, then up (deletion), then left
   (insertion) — FIXED, so the alignment is a function of its inputs and two runs
   of the scorer can never disagree about which characters were confused.

   Ops: {:op :match|:sub :expected c :observed c} | {:op :del :expected c}
        | {:op :ins :observed c}"
  [expected observed]
  (let [t (lev-table expected observed)
        at (fn [i j] (nth (nth t i) j))]
    (loop [i (count expected) j (count observed) ops ()]
      (cond
        (and (zero? i) (zero? j))
        (vec ops)

        (and (pos? i) (pos? j)
             (let [ec (nth expected (dec i))
                   oc (nth observed (dec j))]
               (= (at i j) (+ (at (dec i) (dec j)) (if (= ec oc) 0 1)))))
        (let [ec (nth expected (dec i))
              oc (nth observed (dec j))]
          (recur (dec i) (dec j)
                 (conj ops {:op (if (= ec oc) :match :sub) :expected ec :observed oc})))

        (and (pos? i) (= (at i j) (inc (at (dec i) j))))
        (recur (dec i) j (conj ops {:op :del :expected (nth expected (dec i))}))

        :else
        (recur i (dec j) (conj ops {:op :ins :observed (nth observed (dec j))}))))))

(defn- ops->distance
  "Edit ops that are not matches. THE one place this count is computed — an
   earlier draft had `distance` and `score` each spelling it out, and a docstring
   claiming they could never disagree while nothing pinned them together."
  [ops]
  (count (remove #(= :match (:op %)) ops)))

(defn distance
  "Levenshtein distance, counted off the same alignment the substitution
   accounting uses, through the same `ops->distance`. `score` calls it, so the
   two cannot drift."
  [expected observed]
  (ops->distance (align expected observed)))

(defn score
  "Grade one observed read-back against the string that was rendered.

   `:exact?` IS THE PRIMARY LABEL and the only parameter-free one. Similarity is
   a continuous signal for locating a threshold; it carries a free cutoff, and a
   positive label must never be read off it."
  [expected observed]
  (let [ops (align expected observed)
        subst (filter #(= :sub (:op %)) ops)
        conf (filter #(same-class? (:expected %) (:observed %)) subst)
        d (ops->distance ops)
        denom (max 1 (count expected) (count observed))]
    {:exact? (= expected observed)
     :distance d
     :similarity (- 1.0 (/ (double d) (double denom)))
     :substitutions (count subst)
     :confusable-substitutions (count conf)
     :other-substitutions (- (count subst) (count conf))
     :insertions (count (filter #(= :ins (:op %)) ops))
     :deletions (count (filter #(= :del (:op %)) ops))
     :confusion-detail (frequencies (map (juxt :expected :observed) conf))}))

(defn grade
  "Parse a raw reply and score it. The path a campaign uses.

   A `:no-recovery` or `:unparseable` reply is a MISS and carries NO similarity:
   an edit distance between a 12-character string and the literal token
   `NORECOVERY` is a number with no meaning, and a threshold search that pooled
   it with real reads would be fitting to the sentinel's spelling. §7 maps a miss
   to UNKNOWN, which is a label rather than a score. This is also why a cell of
   nothing but sentinels must not be reported as perfectly reproducible: its
   `:outcome` is constant, but no string was ever read."
  [expected reply]
  (let [p (parse-reply reply)]
    (if (= :recovered (:outcome p))
      (assoc (score expected (:value p)) :outcome :recovered :raw (:raw p))
      {:outcome (:outcome p) :raw (:raw p) :exact? false})))

;; ── What a result may SAY ──────────────────────────────────────────────────

(def banned-claim-tokens
  "Vocabulary no message emitted from a read-back campaign may contain.
   `docs/UI-QUALITY-CONTRACTS.md` §0 puts hardware-scoped legibility OUT OF SCOPE
   for this repository — not pending, OUT — so a message naming a lighting
   condition, a person, or a conformance badge would claim something no
   measurement here imposed.

   IT IS A FLOOR, NOT A PROOF. No list can be shown adequate against a phrase
   nobody thought of, and `claim-problems` passing means only that these
   substrings are absent. Treat a clean result as the absence of the known
   failures, never as a certificate.

   IT OVER-FIRES, AND THAT IS THE SAFE DIRECTION: `vision` matches `revision`,
   `night` matches nothing here but would match a product name, `user` matches
   `useralt`. A false finding costs a rewording; a missed one ships an
   over-claim. FOR MESSAGES ONLY — never run it over a provenance VALUE, where a
   `:model-id` legitimately containing `vision` is data, not a claim."
  ["sunlight" "daylight" "glare" "night" "darkness" "panel"
   "legib" "readab" "operator" "wcag" "complian" "conform" "badge"
   "accessib" "vision" "eyesight"
   "human" "person" "people" "pilot" "crew" "viewer" "observer" "user"
   "naked eye" "by eye"])

(defn pass-message
  "The ONE sanctioned sentence a positive read-back result may carry.

   It names the reader, the level, and nothing else. Everything a reviewer would
   want it to also say — that a person could read it, that a screen is legible,
   that a condition was survived — is exactly what the measurement did not
   impose."
  [level]
  (str "a machine reader recovered the string at level " (pr-str level)))

(defn claim-problems
  "Findings against `banned-claim-tokens` in a message. Empty means none of the
   known over-claiming substrings is present — see the ban list's docstring for
   why that is a floor rather than a certificate.

   A NIL OR BLANK MESSAGE IS ITSELF A FINDING. Without that clause this
   function's clean answer and its nothing-to-report answer are the same empty
   vector, which is the exact defect the suite's own non-vacuity test exists to
   refuse; and `nil` used to reach `str/lower-case` and throw."
  [message]
  (cond
    (nil? message) [{:clause :no-message :detail nil}]
    (str/blank? (str message)) [{:clause :empty-message :detail message}]
    :else (let [lower (str/lower-case (str message))]
            (vec (for [t banned-claim-tokens
                       :when (str/includes? lower t)]
                   {:clause :banned-claim-token :detail t :message message})))))

;; ── The stimulus prompt ────────────────────────────────────────────────────

(def read-back-prompt
  "The instruction sent with EVERY cell image, byte-identical for all of them.

   Constant by construction, which is the deterministic half of the
   unguessability check: a template that cannot vary with the cell carries zero
   bits about the answer. The empirical half is the no-image control arm, and
   only that one can settle whether the model can guess.

   IT IS BUILT FROM `no-recovery-sentinel` rather than repeating it, so the two
   cannot drift — and it is held to `banned-claim-tokens` in the test, which is a
   use beyond that list's original job and is deliberate: a prompt naming
   legibility primes the reader with the very frame this protocol refuses to draw
   a conclusion in."
  (str "The image contains exactly one string of characters.\n"
       "Reply with that string and nothing else: no prose, no quotes, no "
       "explanation, no alternatives.\n"
       "Preserve case exactly. If you cannot determine every character, reply "
       "with the single word " no-recovery-sentinel "."))

(defn prompt-for
  "The prompt for a cell. Takes the cell so the seam is real and a leak is one
   line away — and returns the constant regardless, which is the property the
   test pins."
  [_cell]
  read-back-prompt)

;; ── Corpus ─────────────────────────────────────────────────────────────────

(defn stimulus-id
  "Addresses one STRING: the content class and the draw index, and deliberately
   NOT the level. Every rung of one cell's degradation ladder therefore renders
   the SAME string, which is what makes the ladder a sweep of one variable."
  [content draw]
  (str "content=" (pr-str content) "|draw=" draw))

(defn cell-id
  "Addresses one rendered CELL: a stimulus at a level.

   Derived from the grid coordinates ONLY — never from the string — so a harness
   may render the id onto the card, or log it, without leaking a bit of the
   answer."
  [content level draw]
  (str (stimulus-id content draw) "|level=" (pr-str level)))

(def default-spec
  {:master-seed "CHANGE-ME"
   :contents [:label]
   :levels [1]
   ;; ONE draw is enough to render a ladder and NOT enough to tell a hard string
   ;; from a hard level. `docs/VLM-READBACK-PROTOCOL.md` §4.1 says a campaign
   ;; needs several; this default is the smallest thing that runs, not a
   ;; recommendation, and the instrument cannot tell the difference so it does
   ;; not refuse.
   :draws 1
   :length 12
   :confusables 4})

(defn gen-corpus
  "The answer key: one record per (content, level, draw).

   REFUSES rather than warns. A configuration whose entropy sits below
   `min-entropy-bits`, or whose confusable count cannot fit the length, yields a
   corpus that looks fine and measures guessing — so it throws, and the campaign
   never starts.

   ORDER OF THE GUARDS IS PART OF THE CONTRACT: the alphabet is checked first, so
   an inconsistent alphabet preempts every later clause. That is deliberate — a
   corpus built on a broken alphabet is not worth diagnosing further — and it is
   why a mutation to the pools shows up as neighbouring tests ERRORING rather
   than failing.

   `:draw` VARIES THE STRING; `:level` DOES NOT. The repeated-run axis is
   deliberately absent from the corpus: an identical-input replicate reuses the
   same rendered cell, so it belongs to the campaign runner."
  [spec]
  (let [{:keys [master-seed contents levels draws length confusables] :as s}
        (merge default-spec spec)
        problems (alphabet-problems)]
    (when (seq problems)
      (throw (ex-info "readback-strings: alphabet is inconsistent" {:problems problems})))
    (when-not (<= 0 confusables length)
      (throw (ex-info "readback-strings: confusables must fit within length"
                      {:length length :confusables confusables})))
    (let [bits (entropy-bits length confusables)]
      (when (< bits min-entropy-bits)
        (throw (ex-info "readback-strings: configuration is below the entropy floor"
                        {:bits bits :floor min-entropy-bits
                         :length length :confusables confusables}))))
    (vec (for [c contents, l levels, dr (range draws)
               :let [sid (stimulus-id c dr)]]
           {:cell-id (cell-id c l dr)
            :stimulus-id sid
            :content c
            :level l
            :draw dr
            :string (gen-string master-seed sid s)}))))

(def required-provenance-keys
  "What a campaign MUST record beside its numbers for the labelled set to stay
   interpretable. Every one can invalidate a label on its own: a renderer change
   moves pixels, a font-set change moves glyphs, a model change moves the reader.
   None is knowable from inside this namespace, which is exactly why they are
   demanded rather than defaulted."
  [:renderer-commit :controls-wasm-sha256 :font-set :model-id :harness-version
   :image-encoding :runs-per-cell :campaign-date])

(defn provenance-problems
  "Findings against `required-provenance-keys`. A missing or blank value is a
   finding: a labelled set that cannot say what produced it cannot be retired
   when its producer moves, and an unretirable stale label is worse than none."
  [m]
  (vec (for [k required-provenance-keys
             :let [v (get m k)]
             :when (or (nil? v) (and (string? v) (str/blank? v)))]
         {:clause :missing-provenance :detail k})))

(defn manifest
  "Everything about a corpus derivable without running anything. The campaign
   adds `:provenance`, which must pass `provenance-problems`."
  [spec]
  (let [{:keys [length confusables] :as s} (merge default-spec spec)]
    {:protocol-version protocol-version
     :spec s
     :alphabet (str/join alphabet)
     :alphabet-size (count alphabet)
     :emitted-confusables (str/join emitted-confusables)
     :confusion-classes (mapv #(str/join (sort %)) confusion-classes)
     :space-size (str (space-size length confusables))
     :entropy-bits (entropy-bits length confusables)
     :entropy-floor min-entropy-bits
     :no-recovery-sentinel no-recovery-sentinel
     :required-provenance-keys required-provenance-keys
     :scope (pass-message :LEVEL)}))

;; ── CLI ────────────────────────────────────────────────────────────────────

(defn- parse-args [args]
  (into {} (for [[k v] (partition 2 args)]
             [(keyword (str/replace k #"^--" "")) v])))

(defn- csv [s] (mapv str/trim (str/split s #",")))

(defn -main [& args]
  (let [a (parse-args args)
        spec (cond-> {}
               (:seed a) (assoc :master-seed (:seed a))
               (:length a) (assoc :length (parse-long (:length a)))
               (:confusables a) (assoc :confusables (parse-long (:confusables a)))
               (:contents a) (assoc :contents (csv (:contents a)))
               (:levels a) (assoc :levels (csv (:levels a)))
               (:draws a) (assoc :draws (parse-long (:draws a))))
        problems (alphabet-problems)]
    (println ";; readback-strings: stimulus corpus for docs/VLM-READBACK-PROTOCOL.md")
    (println ";; ANSWER KEY. Never place this output, or any line of it, in a model's context.")
    (when (seq problems)
      (binding [*out* *err*]
        (println "readback-strings: alphabet problems:")
        (doseq [p problems] (println " " (pr-str p))))
      (System/exit 1))
    (let [mf (manifest spec)
          claims (claim-problems (:scope mf))]
      ;; The ONE emitting path in this namespace, held to its own ban list. It
      ;; does not make the document's "the instrument enforces this" true for a
      ;; campaign's report, which is written elsewhere and routed through nothing
      ;; — see `claim-problems`, which a campaign must call itself.
      (when (seq claims)
        (binding [*out* *err*]
          (println "readback-strings: the manifest scope over-claims:")
          (doseq [c claims] (println " " (pr-str c))))
        (System/exit 1))
      (println)
      (println ";; manifest")
      (prn mf)
      (println)
      (println ";; corpus")
      (doseq [c (gen-corpus spec)] (prn c))
      (binding [*out* *err*]
        (printf "entropy: %.2f bits/string (floor %.1f), alphabet %d, space %s%n"
                (:entropy-bits mf) min-entropy-bits (:alphabet-size mf) (:space-size mf))))))
