(ns devcards.standard-brief-test
  "Unit tests for the UI-standard briefing generator. Pure: no wasm, no
   GraalWasm context, no committed render — every input is passed in.

   THE ONE PROPERTY THESE TESTS EXIST FOR. The briefing's whole claim is
   that its values are READ FROM THE LIVE SOURCES, so the drift gate over
   it means something. A test that merely asserts the page contains `0`,
   or contains the word `lv_label`, would pass just as happily against a
   hand-typed string — its pass value equals its nothing-ran value, and it
   would ratify exactly the frozen copy the generator exists to prevent.

   So EVERY assertion here is paired with a CONTROL: the same rendering
   run against a MUTATED source, asserting the output moved with it and no
   longer carries the live value. A frozen page fails both halves; a live
   one passes both. Where the assertion is a throw, the control is the
   happy path that must NOT throw; where it is byte-equality, the control
   is a re-render of identical inputs, which pins determinism so the
   inequalities above it cannot be noise."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.deadzone :as deadzone]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]
            [devcards.layers :as layers]
            [devcards.lvgl-classes :as lvgl-classes]
            [devcards.opa :as opa]
            [devcards.overlap :as overlap]
            [devcards.standard-brief :as sb]))

;; ── Mutants: one source moved, everything else live ─────────────────────
(def ^:private live-classes lvgl-classes/starter-table)

(def ^:private mutant-classes
  "The live table with ONE row flipped — lv_label is `:text` /
   `:interactive? false` live, so both cells move at once and neither
   assertion can pass by matching the other's column."
  (lvgl-classes/merge-consumer
   {:types {"lv_label" {:role :interactive :interactive? true}}}))

(def ^:private inert-classes
  "The two example widgets declared out of the pointer path, which is what
   makes the worked example's finding disappear. `:role :structural` (not
   `:interactive`) because the table refuses `:role :interactive` with
   `:interactive? false` as a contradiction."
  (lvgl-classes/merge-consumer
   {:types {"lv_button" {:role :structural :interactive? false}
            "lv_slider" {:role :structural :interactive? false}}}))

(defn- with-gap-default
  "The live overlap producer with only its declared `:gap-px` DEFAULT
   moved — the exact edit the drift gate must catch."
  [n]
  (assoc-in overlap/producer [:thresholds :gap-px :default] n))

(def ^:private live-doc
  "The producer's own `:doc` for the knob the tests key on."
  (get-in overlap/producer [:thresholds :gap-px :doc]))

(defn- opts
  "Full brief inputs, live by default, with overrides folded in."
  [& {:as overrides}]
  (merge {:contract-text "CONTRACT-ALPHA"
          :classes live-classes
          :producers sb/reviewed-producers
          :example-producer overlap/producer}
         overrides))

;; ── classification-md ───────────────────────────────────────────────────
(deftest classification-md-reads-the-live-table
  (testing "each row carries the class's LIVE :role and :interactive?; the
            same rendering over a table with that row flipped carries the
            mutated values and not the live ones"
    (let [live (sb/classification-md live-classes)
          mutant (sb/classification-md mutant-classes)]
      (is (str/includes? live "| `lv_label` | `:text` | `false` |"))
      (is (not (str/includes? live "| `lv_label` | `:interactive` | `true` |"))
          "control: the live page must NOT already contain the mutant row")
      (is (str/includes? mutant "| `lv_label` | `:interactive` | `true` |"))
      (is (not (str/includes? mutant "| `lv_label` | `:text` | `false` |"))
          "control: the mutated page must NOT still contain the live row")
      (is (not= live mutant)))))

(deftest classification-md-is-sorted-and-total
  (testing "rows are class-name sorted (byte-stable page) and every declared
            type appears — a subset would publish a standard that silently
            excuses whatever it omitted"
    (let [md (sb/classification-md live-classes)
          classes (sort (keys (:types live-classes)))
          rows (keep #(second (re-find #"^\| `([^`]+)` \|" %)) (str/split-lines md))]
      (is (= classes rows))
      (is (not= (reverse classes) rows)
          "control: the sorted assertion is only meaningful if the reversed
           order would fail it")
      (is (seq classes) "control: an empty table would make the equality vacuous"))))

(deftest classification-md-states-the-totality-posture
  (testing "the :default posture is READ from the table, not asserted in prose"
    (let [live (sb/classification-md live-classes)
          defaulted (sb/classification-md
                     (assoc live-classes :default {:interactive? false
                                                   :role :structural}))]
      (is (str/includes? live "NO `:default`"))
      (is (not (str/includes? live "The table declares a `:default`"))
          "control: the live table declares none, so the other branch must
           be absent")
      (is (str/includes? defaulted "The table declares a `:default`"))
      (is (not (str/includes? defaulted "NO `:default`"))
          "control: the defaulted table must not also claim totality"))))

(deftest classification-md-refuses-an-empty-table
  (testing "an empty :types throws rather than publishing an empty standard"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :types"
                          (sb/classification-md {:types {}})))
    (is (string? (sb/classification-md live-classes))
        "control: the live table must NOT trip the same guard")))

;; ── thresholds-md ───────────────────────────────────────────────────────
(deftest thresholds-md-carries-the-declared-default
  (testing "the published default IS the producer's declared default: move it
            and the row moves with it"
    (let [live (sb/thresholds-md sb/reviewed-producers)
          mutant (sb/thresholds-md [(with-gap-default 7) layers/producer])]
      (is (str/includes? live "| `:overlap` | `:overlap/gap-px` | `0` |"))
      (is (not (str/includes? live "| `:overlap` | `:overlap/gap-px` | `7` |"))
          "control: the live page must not already carry the mutant default")
      (is (str/includes? mutant "| `:overlap` | `:overlap/gap-px` | `7` |"))
      (is (not (str/includes? mutant "| `:overlap` | `:overlap/gap-px` | `0` |"))
          "control: the mutated page must not still carry the live default"))))

(deftest thresholds-md-carries-the-producers-own-doc
  (testing "the 'what it means' column is the producer's :doc, not prose
            written here"
    (let [live (sb/thresholds-md sb/reviewed-producers)
          mutant (sb/thresholds-md
                  [(assoc-in overlap/producer [:thresholds :gap-px :doc]
                             "MUTANT-DOC-SENTINEL")
                   layers/producer])]
      (is (str/includes? live live-doc))
      (is (not (str/includes? live "MUTANT-DOC-SENTINEL"))
          "control: the sentinel must be absent from the live render")
      (is (str/includes? mutant "MUTANT-DOC-SENTINEL"))
      (is (not (str/includes? mutant live-doc))
          "control: the mutated render must not still carry the live doc"))))

(deftest thresholds-md-uses-the-registry-key-encoding
  (testing "the consumer key comes from findings/threshold-key, so the page
            cannot disagree with the key a consumer must actually supply —
            proved with a NAMESPACED producer id, whose key the plain
            (name id) encoding would get wrong"
    (let [ns-producer (assoc overlap/producer :id :acme/overlap)
          md (sb/thresholds-md [ns-producer])]
      (is (= :acme.overlap/gap-px (findings/threshold-key :acme/overlap :gap-px)))
      (is (str/includes? md "`:acme.overlap/gap-px`"))
      (is (not (str/includes? md "`:overlap/gap-px`"))
          "control: a hand-built \"<name>/<key>\" string would emit this"))))

(deftest thresholds-md-when-no-rule-declares-a-knob
  (testing "a roster with no thresholds says so in prose instead of emitting a
            header-only table that reads like a published (empty) set"
    (let [none (sb/thresholds-md [(dissoc overlap/producer :thresholds)])
          some- (sb/thresholds-md sb/reviewed-producers)]
      (is (= "No rule in the roster above declares a threshold." none))
      (is (str/includes? some- "| rule | consumer key | default |")
          "control: the live roster DOES render the table"))))

;; ── producers-md ────────────────────────────────────────────────────────
(deftest producers-md-reads-each-rules-live-requires
  (testing "the context column is the rule's declared :requires — the layer
            contract needs the proxy rects and the overlap rule does not, and
            the page must show that difference rather than one shared list.

            The roster is spelled out here rather than taken from
            `sb/reviewed-producers`: the published roster deliberately holds
            only rules this repo ARMS, and `layers/producer` is armed by no
            lane. What this test pins is that the column is read per-rule from
            each producer's own `:requires`, which needs two rules that differ
            — whether either is published is `reviewed-producers`' business
            and is pinned below."
    (let [rows (into {} (for [line (str/split-lines (sb/producers-md [overlap/producer
                                                                      layers/producer]))
                              :let [id (second (re-find #"^\| `([^`]+)` \|" line))]
                              :when id]
                          [id line]))]
      (is (str/includes? (get rows ":layers") "`:proxy-rects`"))
      (is (not (str/includes? (get rows ":overlap") "`:proxy-rects`"))
          "control: the overlap rule declares no such input, so a page that
           printed one shared list would fail here")
      (is (str/includes? (get rows ":overlap") "`:classes`"))
      (is (not (str/includes? (get rows ":layers") "`:classes`"))
          "control: the mirror of the above"))))

;; ── the worked example ──────────────────────────────────────────────────
(deftest example-finding-is-a-live-producer-run
  (testing "the example is what the rule RETURNS over the synthetic tree —
            its geometry, its wording, its keys"
    (let [f (sb/example-finding overlap/producer live-classes)]
      (is (= sb/example-card-id (:card f)))
      (is (= :overlap (:invariant f)))
      (is (= "lv_button#2 vs lv_slider#3" (:node f)))
      (is (str/includes? (:detail f) "overlap depth 10px"))
      (is (= [:card :detail :invariant :node] (sort (keys f)))))))

(deftest example-finding-follows-the-classification-table
  (testing "declare the two example widgets out of the pointer path and the
            example DISAPPEARS — the control that proves it was computed and
            not typed out"
    (is (some? (sb/example-finding overlap/producer live-classes)))
    (is (nil? (sb/example-finding overlap/producer inert-classes)))))

(deftest brief-md-refuses-an-empty-example
  (testing "a briefing whose worked example found nothing throws: an empty
            example block teaches the finding shape wrong, silently"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"worked example"
                          (sb/brief-md (opts :classes inert-classes))))
    (is (string? (sb/brief-md (opts)))
        "control: the live inputs must NOT trip the same guard")))

;; ── the page ────────────────────────────────────────────────────────────
(deftest brief-md-opens-with-the-do-not-edit-banner
  (testing "the generated page carries docgen's banner naming the regenerate
            command — the reader who spots a stale value must be pointed at
            the generator, not left editing the output"
    (let [md (sb/brief-md (opts))]
      (is (str/starts-with? md "<!--\nGENERATED by the devcards gallery generator"))
      (is (str/includes? md sb/regen-cmd))
      (is (not (str/starts-with? md "# UI standard"))
          "control: the banner must precede the title, not follow it"))))

(deftest brief-md-embeds-the-contract-verbatim
  (testing "the contract text arrives byte-for-byte, not summarised: a page
            built from a different contract carries THAT text and not the
            other"
    (let [alpha (sb/brief-md (opts :contract-text "CONTRACT-ALPHA\n\n## §9 alpha"))
          beta (sb/brief-md (opts :contract-text "CONTRACT-BETA\n\n## §9 beta"))]
      (is (str/includes? alpha "CONTRACT-ALPHA\n\n## §9 alpha"))
      (is (not (str/includes? alpha "CONTRACT-BETA"))
          "control: alpha's page must not carry beta's text")
      (is (str/includes? beta "CONTRACT-BETA\n\n## §9 beta"))
      (is (not (str/includes? beta "CONTRACT-ALPHA"))
          "control: and the mirror"))))

(deftest brief-md-moves-with-every-source
  (testing "the page is a FUNCTION of its four inputs — move any one and the
            bytes change. The determinism control is what makes those
            inequalities evidence rather than noise: identical inputs must
            render identical bytes, so the only thing that moved the page was
            the mutated source."
    (let [live (sb/brief-md (opts))]
      (is (= live (sb/brief-md (opts)))
          "control: identical inputs render identical bytes")
      (is (not= live (sb/brief-md (opts :contract-text "SOMETHING-ELSE"))))
      (is (not= live (sb/brief-md (opts :classes mutant-classes))))
      (is (not= live (sb/brief-md (opts :producers [(with-gap-default 3)
                                                    layers/producer]))))
      ;; The example producer is swapped for a DIFFERENT RULE rather than a
      ;; retuned copy of the same one: raising overlap's gap-px cannot move
      ;; this page, because the two example boxes already share pixels and
      ;; the rule's message branches on that, not on the threshold. A mutant
      ;; that cannot change the output is not a control.
      (is (not= live (sb/brief-md (opts :example-producer layers/producer)))))))

(deftest brief-md-carries-the-live-values-it-was-built-from
  (testing "the assembled page — not merely its sections — carries the live
            threshold default, the live classification row and the live rule
            roster, so a section that stopped being spliced in would fail here
            rather than only in its own unit test"
    (let [live (sb/brief-md (opts))
          mutant (sb/brief-md (opts :classes mutant-classes
                                    :producers [(with-gap-default 7)
                                                layers/producer]))]
      (is (str/includes? live "| `:overlap` | `:overlap/gap-px` | `0` |"))
      (is (str/includes? live "| `lv_label` | `:text` | `false` |"))
      (is (str/includes? live live-doc))
      (is (not (str/includes? live "| `:overlap` | `:overlap/gap-px` | `7` |"))
          "control: the live page carries none of the mutant's values")
      (is (str/includes? mutant "| `:overlap` | `:overlap/gap-px` | `7` |"))
      (is (str/includes? mutant "| `lv_label` | `:interactive` | `true` |")))))

(deftest the-exemption-paragraph-is-DERIVED-from-the-key-set
  (testing "this paragraph was a HARDCODED STRING reading 'per card and per
            invariant', and it stayed that way through the commit that added
            the outcome, mode and reason conjuncts to `invariants/exempt?`.
            Nothing went red: `make -f renderer.mk standard-brief` regenerates
            and diffs the page, so it compares the generator against ITSELF
            and a stale literal inside it is invisible.

            THIS CANARY CLOSES THE ROT HALF ONLY. It asserts every key in the
            LIVE set is named, so an axis added later and not rendered reds
            it — the original defect. It does NOT detect a RE-HARDCODING:
            measured, a literal reproducing today's text satisfies every
            assertion below, because they are substring checks against
            today's key names. Detecting that needs an oracle for 'this
            string was COMPUTED', which a substring assertion cannot be, so
            that half of the seam is open and stays open.
            REVERT-TO-BREAK: replace the `exemption-match-keys` splice in
            `exemption-contract-md` with a literal naming every axis but
            `:act/reason`."
    (let [para (sb/exemption-contract-md)]
      (doseq [k invariants/exemption-match-keys]
        (is (str/includes? para (str "`" k "`"))
            (str "the brief does not name the match axis " k)))
      (doseq [k invariants/exemption-proof-keys]
        (is (str/includes? para (str "`" k "`"))
            (str "the brief does not name the proof key " k)))))
  (testing "and it reaches the ASSEMBLED page, not just its own fn — a
            paragraph that stopped being spliced in would pass the loop above
            and teach nothing"
    (let [live (sb/brief-md (opts))]
      (is (str/includes? live "`:act/test-mode`"))
      ;; The THREE absence behaviours, pinned SEPARATELY, because collapsing
      ;; them into one sentence is how this paragraph came to tell the
      ;; reviewing agent that an absent axis matches ANY value. Measured: an
      ;; entry omitting :act/test-mode does NOT match a :manual finding, it
      ;; pins the axis to :automatic; only :node matches anything; and
      ;; :act/reason is not omittable by choice at all.
      (is (str/includes? live "matches ANY node when absent"))
      (is (str/includes? live "absent reads the DEFAULT"))
      (is (str/includes? live "REQUIRED when `:act/outcome` names a reasoned"))))
  (testing "and the KEY LIST being derived does NOT make the paragraph's
            MEANING derived — the half that rotted next. When :owner and
            :expires joined the proof set the splice picked them up by itself,
            while the prose around it still read 'both non-blank' and said
            nothing about EXPIRY at all: an agent following this page would
            write a dateless waiver and meet a clause the page never named.
            The horizon is spliced from `invariants/waiver-horizon-days`, so
            changing the constant without changing the page reds here.
            REVERT-TO-BREAK: type the number into `exemption-contract-md`
            instead of splicing it, then change `waiver-horizon-days`."
    (let [para (sb/exemption-contract-md)]
      (is (str/includes? para (str "at most " invariants/waiver-horizon-days
                                   " days out")))
      (is (str/includes? para "AN EXPIRED WAIVER IS A HARD FAILURE"))
      (is (not (str/includes? para "both non-blank"))
          "CONTROL: the retracted wording is GONE, not sitting beside the
           correction — a four-key proof set is not 'both'")))
  (testing "CONTROL: every retracted wording is GONE from the page, so each
            correction is a REPLACEMENT and not an addition alongside the
            stale claim. Pinning the true sentence alone cannot see that —
            the page can carry both and still pass every assertion above,
            measured — so each retraction owes its own negation here.

            THESE ARE LITERAL-STRING NEGATIONS OF THREE EXACT SENTENCES and
            they do not survive rewording: measured, a PARAPHRASE carrying
            the identical false meaning passes all of them. A substring
            assertion cannot cover paraphrase, and no matcher here should
            pretend to — read these as pinning the three retractions by
            name, not the idea behind them."
    (let [live (sb/brief-md (opts))]
      (is (not (str/includes? live "per card and per invariant")))
      (is (not (str/includes? live "an absent axis matches ANY value")))
      (is (not (str/includes? live "findings of EITHER mode"))))))

;; ── the published roster ────────────────────────────────────────────────
(deftest reviewed-producers-references-the-live-rules
  (testing "the roster holds the producer VARS themselves, so a rule's
            declaration cannot be transcribed here and then drift from the
            rule. `identical?` is the discriminating test: an equal copy is
            not the same object, which the control proves it can tell apart."
    (is (identical? overlap/producer (first sb/reviewed-producers)))
    (is (identical? deadzone/producer (second sb/reviewed-producers)))
    (is (identical? opa/producer (nth sb/reviewed-producers 2)))
    (testing "and the roster is EXACTLY the set some lane ARMS, asserted in
              BOTH directions against `devcards.lanes` rather than against a
              transcribed list. The negative half alone — `layers/producer`
              absent — is what shipped first, and it cannot catch the failure
              that actually happened: `deadzone/producer` was armed on both
              lanes and left off this page, with the whole suite green. A
              roster that may silently omit an armed rule publishes a
              threshold table the review agent reads as complete."
      (let [armed (distinct (concat lanes/atomic-producers
                                    lanes/composition-producers))]
        (is (= (set (map :id (filter :thresholds armed)))
               (set (map :id sb/reviewed-producers)))
            "the roster is derived, not transcribed: exactly the ARMED
             producers that DECLARE a threshold. The excluded ones are excluded
             by a property rather than by a list — :tree-by-expect, :tree and
             :emission-by-mode publish no knob, so there is nothing for the
             page to state about them. Arming a rule that has a threshold and
             forgetting this roster now fails here.")
        (is (seq (remove :thresholds armed))
            "control: some armed producer really does lack thresholds, so the
             filter above is discriminating rather than vacuously total"))
      (is (not (some #{layers/producer} sb/reviewed-producers))
          "layers/producer is armed by no lane here, so its threshold must not
           print beside numbers that judged every render"))
    (is (not (identical? overlap/producer (into {} overlap/producer)))
        "control: identical? distinguishes an equal copy, so the assertions
         above are about identity and not merely value")
    (is (= sb/reviewed-producers (findings/validate-producers! sb/reviewed-producers))
        "the roster is registry-valid — a malformed one fails generation")))

(deftest reviewed-producers-publishes-only-what-a-lane-ARMS
  (testing "`layers/producer` is armed by NO lane in this repo, so the page
            must not publish its threshold beside the armed rule's — a number
            that judged no render, printed next to one that judged every
            render, reads as coverage, and the page's reader (a model holding
            this page and nothing else) has no docstring to disambiguate them.
            REVERT-TO-BREAK: put `layers/producer` back in
            `sb/reviewed-producers`."
    (is (not (some #{layers/producer} sb/reviewed-producers)))
    (is (not (str/includes? (sb/thresholds-md sb/reviewed-producers)
                            "`:layers/gap-px`")))
    (is (str/includes? (sb/brief-md (opts)) "`:overlap/gap-px`")
        "control: the ARMED rule's key is still published, so the assertion
         above is about which rules are listed and not about the table
         having disappeared")
    (is (str/includes? (sb/thresholds-md [overlap/producer layers/producer])
                       "`:layers/gap-px`")
        "control: the renderer CAN emit that row — it is the roster that
         omits it, not the generator that cannot print it")))
