(ns devcards.readback-strings-test
  "Unit proofs for `tools/devcards/dev/readback_strings.clj`, the stimulus
   instrument behind `docs/VLM-READBACK-PROTOCOL.md`.

   WHAT THIS SUITE IS, AND WHAT IT IS NOT. It runs no model, renders no card and
   reads no framebuffer. Every clause here is arithmetic or string algebra on the
   generator's own output, which is exactly the reach it should have: the
   protocol's empirical halves — that a model cannot guess the string without the
   image, and how far its answers move run to run on identical input — are
   CAMPAIGN measurements and cannot be settled by a unit test. What a unit test
   CAN settle is that the instrument does not hand the campaign a corpus that
   makes those measurements meaningless, and that is what each clause below is.

   THE SPACE-SIZE AND DISTANCE CLAUSES CROSS-CHECK AGAINST AN INDEPENDENT PATH,
   never against the implementation restated:

     - `space-size` is a closed form; `enumerate-space` builds every string of a
       shrunken alphabet by brute force and counts the ones that qualify. A
       wrong binomial or a wrong pool exponent cannot survive both.
     - `distance` counts non-match ops off `align`'s BACKTRACE; `lev-ref` is a
       plain forward DP that never backtracks. They agree only if the backtrace
       is right, which is the part a hand-checked example set would miss.

   REVERT-TO-BREAK lines name the ONE production expression whose reversion is
   intended to turn that clause red. THE ONES MARKED PROVEN ARE PROVEN, by
   `tools/devcards/dev/readback_mutate.sh`, which runs each with a named canary
   and a named CONTROL that must stay green and commits its output as
   `readback_mutation_evidence.txt`. AN UNMARKED MARKER IS DOCUMENTATION OF AN
   INTENDED BREAK, NOT EVIDENCE OF ONE — an earlier version of this docstring
   claimed every marker was paired with a control, which was wrong by a factor of
   two. If you add a marker, either add it to the harness or leave this sentence
   true."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [readback-strings :as rb])
  (:import (java.util Random)))

;; ── Independent references ─────────────────────────────────────────────────

(defn- lev-ref
  "Textbook forward Levenshtein DP. Deliberately does NOT backtrack, so it is
   independent of `rb/align` in the one place `rb/distance` could be wrong."
  [a b]
  (let [m (count a) n (count b)]
    (last
     (reduce (fn [prev i]
               (reduce (fn [cur j]
                         (conj cur
                               (if (zero? j)
                                 i
                                 (min (inc (nth prev j))
                                      (inc (nth cur (dec j)))
                                      (+ (nth prev (dec j))
                                         (if (= (nth a (dec i)) (nth b (dec j))) 0 1))))))
                       [] (range (inc n))))
             (vec (range (inc n)))
             (range 1 (inc m))))))

;; A shrunken alphabet small enough to enumerate exhaustively: 3 confusables,
;; 2 singletons, length 4. 5^4 = 625 strings in total, of which the qualifying
;; subset is what `space-size` claims to count.
(def ^:private tiny-conf [\0 \O \1])
(def ^:private tiny-single [\A \C])
(def ^:private tiny-len 4)
(def ^:private tiny-k 2)

(defn- enumerate-space
  "Every string of `tiny-len` over the shrunken alphabet that carries EXACTLY
   `tiny-k` characters from the confusable pool. Brute force; no formula."
  []
  (let [pool (concat tiny-conf tiny-single)
        conf (set tiny-conf)]
    (->> (reduce (fn [acc _] (for [s acc, c pool] (str s c)))
                 [""] (range tiny-len))
         (filter #(= tiny-k (count (filter conf %))))
         set)))

(defn- tiny-string [sid]
  (rb/gen-string "tiny-seed" sid {:length tiny-len :confusables tiny-k}))

(defn- with-tiny-pools [f]
  (with-redefs [rb/emitted-confusables tiny-conf
                rb/singleton-chars tiny-single]
    (f)))

(defn- clauses-of [problems] (set (map :clause problems)))

;; ── A. Alphabet closure ────────────────────────────────────────────────────

(deftest alphabet-closure
  (testing "the shipped pools are mutually consistent"
    ;; REVERT-TO-BREAK (PROVEN, harness M8): put a character outside the
    ;; compiled glyph range into `rb/singleton-chars`.
    (is (= [] (rb/alphabet-problems))
        "alphabet-problems must be empty on the shipped pools"))

  (testing "EVERY clause can be shown FIRING, not merely shown quiet"
    ;; The 3-arity is what makes this possible: the pools are load-time defs, so
    ;; `with-redefs` cannot move the derived `alphabet`, and four of the eight
    ;; clauses would otherwise be unreachable from a test. A clause never seen to
    ;; fire is indistinguishable from a clause that cannot.
    (let [ok-classes rb/confusion-classes
          ok-emitted rb/emitted-confusables
          ok-singles rb/singleton-chars]
      (is (contains? (clauses-of (rb/alphabet-problems ok-classes [] ok-singles))
                     :pool-is-empty)
          "an empty confusable pool must be a finding")
      (is (contains? (clauses-of (rb/alphabet-problems ok-classes ok-emitted []))
                     :pool-is-empty)
          "an empty singleton pool must be a finding — gen-string would reach
           (.nextInt r 0) and die with a message naming nothing")
      (is (contains? (clauses-of (rb/alphabet-problems [#{\0 \O} #{\O \Q}]
                                                       ok-emitted ok-singles))
                     :classes-disjoint)
          "two classes sharing a character must be a finding")
      (is (contains? (clauses-of (rb/alphabet-problems [#{\0}] ok-emitted ok-singles))
                     :class-needs-two-members)
          "a one-member class is not a confusion and must be a finding")
      (is (contains? (clauses-of (rb/alphabet-problems [#{\5 \S}] ok-emitted ok-singles))
                     :emitted-confusable-has-no-class)
          "an emitted confusable with no class must be a finding")
      (is (contains? (clauses-of (rb/alphabet-problems ok-classes ok-emitted
                                                       (conj ok-singles \o)))
                     :singleton-is-classed)
          "a singleton that is also a class member must be a finding")
      (is (contains? (clauses-of (rb/alphabet-problems ok-classes ok-emitted
                                                       (conj ok-singles \0)))
                     :pools-overlap)
          "a character in both pools must be a finding")
      (is (contains? (clauses-of (rb/alphabet-problems ok-classes ok-emitted
                                                       (conj ok-singles \u2603)))
                     :outside-compiled-glyph-range)
          "a character with no compiled glyph must be a finding")
      (is (contains? (clauses-of (rb/alphabet-problems ok-classes ok-emitted
                                                       (conj ok-singles \A)))
                     :alphabet-has-duplicates)
          "a duplicated character must be a finding")))

  (testing "the glyph-range clause is what proves the shipped alphabet renderable"
    ;; NOT a second opinion about `rb/alphabet`: this drives the PRODUCTION
    ;; clause with the shipped pools, so deleting that clause from
    ;; `alphabet-problems` reds it. An `(every? ...)` over `rb/alphabet` here
    ;; would have re-read the data and stayed green.
    (is (empty? (filter #(= :outside-compiled-glyph-range (:clause %))
                        (rb/alphabet-problems)))
        "an unrenderable character makes the ground truth unrecoverable by any
         reader, and nothing downstream can tell that from a hard cell"))

  (testing "the singleton rule is applied: no singleton crowds a declared class"
    ;; The rule is a JUDGEMENT about glyph shapes (see the ns docstring) and
    ;; cannot be tested as such. What IS testable is the mechanical half — no
    ;; singleton is a member of a declared class — plus the specific characters
    ;; the rule removed, so a silent re-admission of `L` beside `l` shows up.
    (let [classed (into #{} cat rb/confusion-classes)
          singles (set rb/singleton-chars)]
      (is (empty? (set/intersection classed singles)))
      (doseq [ch [\3 \6 \7 \L \D \Q \G]]
        (is (not (contains? singles ch))
            (str ch " crowds a declared class member and must stay out of the pool"))))))

;; ── B. The exactly-k construction ──────────────────────────────────────────

(deftest exactly-k-confusables
  (testing "every string carries exactly the configured confusable count"
    ;; REVERT-TO-BREAK (PROVEN, harness M1): in `rb/gen-string`, draw the
    ;; non-confusable positions from `alphabet` instead of `singleton-chars`.
    (let [conf (set rb/emitted-confusables)]
      (doseq [n (range 40)
              :let [s (rb/gen-string "exact-k-seed" (str "sid-" n)
                                     {:length 12 :confusables 4})]]
        (is (= 12 (count s)) (str "length for sid-" n))
        (is (= 4 (count (filter conf s)))
            (str "sid-" n " must carry exactly 4 emitted confusables, got "
                 (count (filter conf s)) " in " s)))))

  (testing "the count tracks the configuration rather than a constant"
    (let [conf (set rb/emitted-confusables)]
      (doseq [k [0 1 7 12]]
        (let [s (rb/gen-string "exact-k-seed" (str "k-" k) {:length 12 :confusables k})]
          (is (= k (count (filter conf s)))
              (str "k=" k " must be honoured, got " (count (filter conf s)) " in " s)))))))

;; ── C. Soundness and completeness against a brute-forced space ─────────────

(deftest space-is-exactly-what-the-formula-claims
  (with-tiny-pools
    (fn []
      (let [valid (enumerate-space)]
        (testing "the closed form counts what brute force enumerates"
          ;; REVERT-TO-BREAK (PROVEN, harness M2): drop the binomial factor from
          ;; `rb/space-size`.
          (is (= (bigint (count valid)) (bigint (rb/space-size tiny-len tiny-k)))
              "space-size must equal the brute-forced count"))

        (testing "entropy-bits is log2 of that same count"
          (is (< (abs (- (rb/entropy-bits tiny-len tiny-k)
                         (/ (Math/log (count valid)) (Math/log 2.0))))
                 1e-9)
              "entropy-bits must be log2 of the exact space size"))

        (testing "SOUND: the generator never leaves the space"
          (doseq [n (range 2000)]
            (let [s (tiny-string (str "sound-" n))]
              (is (contains? valid s)
                  (str "generated " s " is outside the declared space")))))

        (testing "COMPLETE: the generator reaches every point of the space"
          ;; A generator stuck in a sub-space would still pass SOUND while
          ;; carrying far less entropy than the manifest advertises.
          (let [hit (into #{} (map #(tiny-string (str "cover-" %)) (range 20000)))]
            (is (= valid hit)
                (str "covered " (count hit) " of " (count valid)
                     " reachable strings; missing "
                     (pr-str (take 5 (sort (set/difference valid hit))))))))))))

;; ── D. Determinism, and the three variance axes ────────────────────────────

(deftest determinism
  (testing "same (master, stimulus) is the same string, every call"
    (is (= (rb/gen-string "S" "c" {:length 12 :confusables 4})
           (rb/gen-string "S" "c" {:length 12 :confusables 4}))))

  (testing "PIN: the draw order and the pools have not moved"
    ;; A GOLDEN, and it is meant to be brittle. If this literal changes, the
    ;; corpus every past campaign rendered is no longer reproducible, so
    ;; `rb/protocol-version` MUST move in the same commit. What it proves is that
    ;; the pools, their ORDER and the sequence of nextInt calls are unchanged. It
    ;; cannot by itself prove CROSS-JVM stability — a single run sees one JVM —
    ;; but the two JVMs this repo uses were measured agreeing on the version-1
    ;; literal (GraalVM CE 25.0.2 in the uber image, OpenJDK 26.0.1 on the host),
    ;; which is what `Random.nextInt`'s specified algorithm predicts and is the
    ;; reason `rand-int`/`shuffle` are banned in the instrument.
    (is (= "WO2Y10XCET9C" (rb/gen-string "pin-seed" "pin-cell"
                                         {:length 12 :confusables 4})))
    (is (= 4 (count (filter (set rb/emitted-confusables)
                            (rb/gen-string "pin-seed" "pin-cell"
                                           {:length 12 :confusables 4}))))
        "the pin must itself satisfy the exactly-k construction"))

  (testing "the master seed and the stimulus id are independent axes"
    (is (not= (rb/gen-string "S1" "c" {:length 12 :confusables 4})
              (rb/gen-string "S2" "c" {:length 12 :confusables 4}))
        "a new master seed must re-mint every stimulus")
    (is (not= (rb/gen-string "S" "c1" {:length 12 :confusables 4})
              (rb/gen-string "S" "c2" {:length 12 :confusables 4}))
        "a new stimulus must get a new string"))

  (testing "the separator makes the two inputs unambiguous"
    ;; Without it, ("ab","c") and ("a","bc") seed identically and two distinct
    ;; stimuli render one string — a silent duplicate in the answer key.
    ;; REVERT-TO-BREAK (PROVEN, harness M7): delete the NUL separator argument
    ;; in `rb/stimulus-seed`.
    (is (not= (rb/stimulus-seed "ab" "c") (rb/stimulus-seed "a" "bc")))))

(deftest the-ladder-sweeps-one-variable
  ;; THE DEFECT THIS PINS: keying the string on the CELL gave every rung of a
  ;; degradation ladder a different string, so a level-to-level difference
  ;; carried string-difficulty variance — which the replicate axis, holding the
  ;; string fixed by definition, can never estimate. A non-monotonic ladder then
  ;; looks like a render defect while being a property of the draw.
  ;; REVERT-TO-BREAK (PROVEN, harness M10): put the level back into
  ;; `rb/stimulus-id`.
  (let [corpus (rb/gen-corpus {:master-seed "ladder" :contents [:a :b :c]
                               :levels [1 2 3 4 5] :draws 4})]
    (testing "the grid has the shape the spec asked for"
      (is (= 60 (count corpus)))
      (is (= 60 (count (set (map :cell-id corpus))))
          "every rendered cell must be separately addressable"))

    (testing "the string is CONSTANT across the levels of one stimulus"
      (doseq [[sid recs] (group-by :stimulus-id corpus)]
        (is (= 1 (count (set (map :string recs))))
            (str "stimulus " sid " rendered " (count (set (map :string recs)))
                 " distinct strings across its ladder; a threshold measured on"
                 " that is confounded with string difficulty"))
        (is (= 5 (count recs)) "each stimulus must span the whole ladder")))

    (testing "the string VARIES across draws, which is the replication axis"
      (let [per-stimulus (into {} (for [[sid recs] (group-by :stimulus-id corpus)]
                                    [sid (first (map :string recs))]))]
        (is (= 12 (count per-stimulus)) "3 contents x 4 draws")
        (is (= 12 (count (set (vals per-stimulus))))
            "two stimuli sharing a string would silently correlate their labels")))

    (testing "the level reaches the cell id and not the stimulus id"
      (is (str/includes? (rb/cell-id :a 7 0) "level="))
      (is (not (str/includes? (rb/stimulus-id :a 0) "level="))))))

;; ── E. The ids leak nothing ────────────────────────────────────────────────

(deftest ids-carry-no-answer
  (testing "the string is not determined by the id"
    ;; A harness may caption a card with its cell id, or log it beside the image.
    ;; That is only safe while the id is independent of the string. Two master
    ;; seeds over ONE id is the test with content in it: an assertion that
    ;; `cell-id` returns what `cell-id` just returned is a tautology, which is
    ;; what stood here before.
    (let [sid (rb/stimulus-id :label 0)
          s1 (rb/gen-string "seed-A" sid {:length 12 :confusables 4})
          s2 (rb/gen-string "seed-B" sid {:length 12 :confusables 4})]
      (is (not= s1 s2) "two seeds, one id: the id cannot determine the string")
      (is (not (str/includes? (rb/cell-id :label 3 0) s1)))
      (is (not (str/includes? (rb/cell-id :label 3 0) s2)))))

  (testing "every coordinate reaches the id it belongs to"
    (is (apply distinct? [(rb/cell-id :a 1 0) (rb/cell-id :b 1 0)
                          (rb/cell-id :a 2 0) (rb/cell-id :a 1 1)]))
    (is (apply distinct? [(rb/stimulus-id :a 0) (rb/stimulus-id :b 0)
                          (rb/stimulus-id :a 1)]))))

;; ── F. The prompt carries zero bits ────────────────────────────────────────

(deftest prompt-is-constant
  (let [corpus (rb/gen-corpus {:master-seed "prompt" :contents [:a :b]
                               :levels [1 2 3] :draws 2})]
    (testing "one template, byte-identical for every cell"
      ;; The deterministic half of the unguessability check: a template that
      ;; cannot vary with the cell carries no information about the answer.
      ;; REVERT-TO-BREAK (PROVEN, harness M6): make `rb/prompt-for` interpolate
      ;; anything from its argument.
      (is (= 1 (count (set (map rb/prompt-for corpus))))
          "a prompt that varies by cell is a side channel"))

    (testing "no answer appears in the prompt"
      (doseq [c corpus]
        (is (not (str/includes? (rb/prompt-for c) (:string c)))
            (str "prompt leaks the answer for " (:cell-id c)))))

    (testing "the prompt asks for the string alone"
      (is (str/includes? rb/read-back-prompt "nothing else"))
      ;; REVERT-TO-BREAK (PROVEN, harness M9): spell `rb/no-recovery-sentinel`
      ;; "UNREADABLE". The sentinel names the OUTCOME, never the render — one
      ;; asking the model for a verdict about the image hands the campaign a
      ;; token that reads like a negative label, which is the protocol's §2.1
      ;; inversion site 1, invited by the instrument.
      (is (str/includes? rb/read-back-prompt rb/no-recovery-sentinel)
          "the give-up token must actually be offered to the reader")
      (is (= [] (rb/claim-problems rb/no-recovery-sentinel))
          (str "the sentinel must not itself claim anything about the render; got "
               (pr-str (rb/claim-problems rb/no-recovery-sentinel))))
      (is (= [] (rb/claim-problems rb/read-back-prompt))
          (str "the prompt must not prime the reader with a legibility frame; got "
               (pr-str (rb/claim-problems rb/read-back-prompt)))))))

;; ── G. The parser ──────────────────────────────────────────────────────────

(deftest parser-is-total-and-verbatim
  (testing "a clean reply is recovered VERBATIM"
    ;; REVERT-TO-BREAK (PROVEN, harness M11): make `rb/parse-reply` upper-case
    ;; its result.
    (is (= {:outcome :recovered :value "0O1lI5S8B2ZA" :raw "0O1lI5S8B2ZA"}
           (rb/parse-reply "0O1lI5S8B2ZA")))
    (is (= "  0O1l  " (:raw (rb/parse-reply "  0O1l  "))))
    (is (= "0O1l" (:value (rb/parse-reply "  0O1l  ")))
        "surrounding whitespace is trimmed; nothing else is touched"))

  (testing "a confusion is NOT repaired on the way to the scorer"
    ;; The single most damaging thing a parser could do here: normalising `o` to
    ;; `0` turns a recorded confusion into a correct answer, and no downstream
    ;; number could ever recover it.
    (is (= "oO1lI5S8B2ZA" (:value (rb/parse-reply "oO1lI5S8B2ZA"))))
    (is (= "abc" (:value (rb/parse-reply "abc"))) "no case folding")
    (is (= "\"ABC\"" (:value (rb/parse-reply "\"ABC\"")))
        "quotes are NOT stripped — the prompt forbade them, so a quoted reply is
         a protocol violation the scorer must see"))

  (testing "the sentinel is its own outcome"
    (is (= :no-recovery (:outcome (rb/parse-reply "NORECOVERY"))))
    (is (= :no-recovery (:outcome (rb/parse-reply "  NORECOVERY  "))))
    (is (= :recovered (:outcome (rb/parse-reply "norecovery")))
        "case matters: a lowercase token is a string, not the sentinel"))

  (testing "everything else is unparseable, and nothing throws"
    (is (= :unparseable (:outcome (rb/parse-reply nil))))
    (is (= :unparseable (:outcome (rb/parse-reply ""))))
    (is (= :unparseable (:outcome (rb/parse-reply "   "))))
    (is (= :unparseable (:outcome (rb/parse-reply "ABC DEF")))
        "two tokens force a choice the parser must not make")
    (is (= :unparseable (:outcome (rb/parse-reply "The string is ABC")))))

  (testing "grade maps a non-recovery to a MISS with NO similarity"
    ;; An edit distance between a 12-character string and the token NORECOVERY
    ;; is a number with no meaning; pooling it into a threshold search fits the
    ;; sentinel's spelling. It is also why a cell of nothing but sentinels must
    ;; not be reported as perfectly reproducible.
    (let [g (rb/grade "0O1lI5S8B2ZA" "NORECOVERY")]
      (is (= :no-recovery (:outcome g)))
      (is (false? (:exact? g)))
      (is (nil? (:similarity g)) "a miss carries no continuous score"))
    (let [g (rb/grade "0O1lI5S8B2ZA" "0O1lI5S8B2ZA")]
      (is (= :recovered (:outcome g)))
      (is (true? (:exact? g)))
      (is (== 1.0 (:similarity g))))
    (let [g (rb/grade "0O1lI5S8B2ZA" "I said ABC")]
      (is (= :unparseable (:outcome g)))
      (is (false? (:exact? g)))
      (is (nil? (:similarity g))))))

;; ── H. What a result may say ───────────────────────────────────────────────

(deftest claims-stay-inside-the-measurement
  (testing "the sanctioned sentence names the reader and the level, nothing more"
    ;; UI-QUALITY-CONTRACTS §0: hardware-scoped legibility is OUT OF SCOPE for
    ;; this repository, so no message here may imply a condition no measurement
    ;; imposed.
    ;; REVERT-TO-BREAK (PROVEN, harness M4): reword `rb/pass-message` to name a
    ;; person.
    (doseq [level [1 2.5 :low "cr-3.0" [:cr 3.0]]]
      (is (= [] (rb/claim-problems (rb/pass-message level)))
          (str "pass-message overclaims at level " (pr-str level) ": "
               (rb/pass-message level))))
    (is (str/includes? (rb/pass-message 3) "machine reader")
        "the sentence must name WHO read it"))

  (testing "the ban is not vacuous"
    ;; A checker whose clean output and whose nothing-to-report output are the
    ;; same empty vector proves nothing until it is shown firing.
    (is (= #{"sunlight" "legib"}
           (set (map :detail (rb/claim-problems "legible in direct sunlight")))))
    (is (seq (rb/claim-problems "WCAG AA conformance badge")))
    (is (seq (rb/claim-problems "a human could read this")))
    (is (seq (rb/claim-problems "visible to the crew")))
    (is (seq (rb/claim-problems "a person could see it")))
    (is (seq (rb/claim-problems "recovered by the naked eye"))))

  (testing "a message that says NOTHING is a finding, not a pass"
    ;; The other half of non-vacuity, and the half that was missing: without
    ;; these clauses an empty report and a sanctioned one return the same [].
    (is (= [:no-message] (map :clause (rb/claim-problems nil)))
        "nil must be a finding rather than an NPE")
    (is (= [:empty-message] (map :clause (rb/claim-problems ""))))
    (is (= [:empty-message] (map :clause (rb/claim-problems "   ")))))

  (testing "the level travels into the check, so a level cannot smuggle a claim"
    (is (seq (rb/claim-problems (rb/pass-message :readable-in-sunlight))))))

;; ── I. The instrument refuses a corpus that would measure guessing ─────────

(deftest entropy-floor-is-enforced
  (testing "a configuration below the floor throws rather than warns"
    ;; REVERT-TO-BREAK (PROVEN, harness M5): delete the `min-entropy-bits`
    ;; comparison in `rb/gen-corpus`.
    (let [e (try (rb/gen-corpus {:master-seed "s" :length 4 :confusables 1})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a 4-character string is guessable and must be refused")
      ;; NIL-SAFE ON PURPOSE. Written as `(ex-message e)` these two throw an NPE
      ;; when the guard is removed, and the suite then reports ERROR where it
      ;; owes a FAIL — a red that says the harness broke rather than which clause
      ;; did.
      (is (str/includes? (str (ex-message e)) "entropy floor")
          (str "expected the entropy-floor message, got " (pr-str (ex-message e))))
      (is (< (:bits (ex-data e) Double/MAX_VALUE) rb/min-entropy-bits)
          (str "expected ex-data to carry the offending bit count, got "
               (pr-str (ex-data e))))))

  (testing "CONTROL: the default configuration is accepted"
    ;; If this went red alongside the clause above, the red would only mean
    ;; gen-corpus throws on everything.
    (is (= 1 (count (rb/gen-corpus {:master-seed "s"})))))

  (testing "an impossible confusable count is refused by its own clause"
    (let [e (try (rb/gen-corpus {:master-seed "s" :length 12 :confusables 13})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (str/includes? (str (ex-message e)) "must fit within length")
          (str "the message must name THIS clause, not the entropy floor; got "
               (pr-str (ex-message e))))))

  (testing "the floor keeps the blind-guess rate negligible against a campaign"
    ;; 10^4 cells at the floor expect ~9e-9 lucky exact matches. The claim is
    ;; that the floor is comfortable, NOT that just below it lucky hits start
    ;; appearing — an earlier message here said the latter, which the arithmetic
    ;; does not support.
    (is (< (* 1e4 (Math/pow 2.0 (- rb/min-entropy-bits))) 1e-8))
    (is (>= (rb/entropy-bits 12 4) rb/min-entropy-bits)
        "the shipped default must clear its own floor")))

;; ── J. The scorer ──────────────────────────────────────────────────────────

(deftest scorer-basics
  (testing "an exact read is exact"
    (let [r (rb/score "AB12" "AB12")]
      (is (true? (:exact? r)))
      (is (zero? (:distance r)))
      (is (== 1.0 (:similarity r)))))

  (testing "a confusable substitution is attributed, an ordinary one is not"
    (let [r (rb/score "0AAA" "OAAA")]
      (is (false? (:exact? r)))
      (is (= 1 (:substitutions r)))
      (is (= 1 (:confusable-substitutions r)))
      (is (zero? (:other-substitutions r))))
    (let [r (rb/score "0AAA" "4AAA")]
      (is (= 1 (:substitutions r)))
      (is (zero? (:confusable-substitutions r)))
      (is (= 1 (:other-substitutions r)))))

  (testing "a character the generator never emits still scores as confusion"
    ;; `o` is in the 0/O class but never rendered. Scoring it as an ordinary
    ;; substitution would hide a real confusion behind an alphabet boundary.
    (is (= 1 (:confusable-substitutions (rb/score "0AAA" "oAAA")))))

  (testing "the documented UNDER-COUNT is real and pinned here"
    ;; `D` crowds `0` but is in no declared class, so it scores ordinary. This is
    ;; the bias the ns docstring names, asserted rather than described, so a
    ;; future widening of the classes has to come here and change it.
    (is (zero? (:confusable-substitutions (rb/score "0AAA" "DAAA"))))
    (is (= 1 (:other-substitutions (rb/score "0AAA" "DAAA")))))

  (testing "insertions and deletions are separated from substitutions"
    (let [r (rb/score "ABCD" "ABXCD")]
      (is (= 1 (:insertions r)))
      (is (zero? (:deletions r))))
    (let [r (rb/score "ABCD" "ABD")]
      (is (= 1 (:deletions r)))
      (is (zero? (:insertions r)))))

  (testing "an empty read is a total miss, not a crash"
    (let [r (rb/score "ABCD" "")]
      (is (false? (:exact? r)))
      (is (= 4 (:distance r)))
      (is (== 0.0 (:similarity r))))))

(deftest distance-agrees-with-an-independent-dp
  (testing "the backtrace-derived distance equals a plain forward DP"
    ;; REVERT-TO-BREAK (PROVEN, harness M3a): change the op filter in
    ;; `rb/ops->distance`.
    (let [r (Random. 20260728)
          pool (vec "0O1lI5S8B2Z49ACEFHJKMNPRTUVWXY")
          draw (fn [n] (str/join (repeatedly n #(nth pool (.nextInt r (count pool))))))]
      (doseq [_ (range 300)]
        (let [a (draw (inc (.nextInt r 10)))
              b (draw (.nextInt r 12))]
          (is (= (lev-ref a b) (rb/distance a b))
              (str "distance disagreed on " (pr-str a) " -> " (pr-str b)))
          ;; `score` used to spell the same count out a second time, under a
          ;; docstring claiming they could never disagree with nothing pinning
          ;; them. Mutating `distance` alone left `scorer-basics` green, which is
          ;; what proved the duplication. Both now go through `ops->distance`,
          ;; and this is the assertion that keeps it that way.
          (is (= (rb/distance a b) (:distance (rb/score a b)))
              (str "score and distance disagreed on " (pr-str a) " -> " (pr-str b)))))))

  (testing "CONTROL: the reference agrees with itself on the trivial cases"
    (is (zero? (lev-ref "ABC" "ABC")))
    (is (= 3 (lev-ref "ABC" "")))
    (is (= 1 (lev-ref "ABC" "ABD")))))

;; ── K. Provenance and the manifest ─────────────────────────────────────────

(deftest provenance-is-demanded
  (testing "the required key SET is pinned, not merely counted"
    ;; An assertion comparing `(count required-provenance-keys)` to
    ;; `(count (provenance-problems {}))` is invariant to the CONTENT of the
    ;; list — drop a key and both sides fall together. That is what stood here,
    ;; and it left the eight keys free to drift from the document that names
    ;; them.
    (is (= [:renderer-commit :controls-wasm-sha256 :font-set :model-id
            :harness-version :image-encoding :runs-per-cell :campaign-date]
           rb/required-provenance-keys)
        "docs/VLM-READBACK-PROTOCOL.md §8 lists these; change both together"))

  (testing "a campaign missing any required key is a finding"
    (is (= (set rb/required-provenance-keys)
           (set (map :detail (rb/provenance-problems {}))))
        "an empty provenance map must produce one finding per required key"))

  (testing "a blank string does not satisfy a key"
    (let [full (zipmap rb/required-provenance-keys (repeat "x"))]
      (is (= [] (rb/provenance-problems full)))
      (is (= [:model-id]
             (map :detail (rb/provenance-problems (assoc full :model-id "  ")))))
      (is (= [:model-id]
             (map :detail (rb/provenance-problems (dissoc full :model-id)))))))

  (testing "the manifest states its own scope and never more"
    (let [m (rb/manifest {:master-seed "s"})]
      (is (= [] (rb/claim-problems (:scope m))))
      (is (>= (:entropy-bits m) rb/min-entropy-bits))
      (is (= (count rb/alphabet) (:alphabet-size m)))
      (is (= rb/no-recovery-sentinel (:no-recovery-sentinel m))
          "a campaign reading the manifest must learn the sentinel from it"))))
