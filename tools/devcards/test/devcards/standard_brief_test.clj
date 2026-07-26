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
            [devcards.findings :as findings]
            [devcards.layers :as layers]
            [devcards.lvgl-classes :as lvgl-classes]
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
            the page must show that difference rather than one shared list"
    (let [rows (into {} (for [line (str/split-lines (sb/producers-md sb/reviewed-producers))
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

;; ── the published roster ────────────────────────────────────────────────
(deftest reviewed-producers-references-the-live-rules
  (testing "the roster holds the producer VARS themselves, so a rule's
            declaration cannot be transcribed here and then drift from the
            rule. `identical?` is the discriminating test: an equal copy is
            not the same object, which the control proves it can tell apart."
    (is (identical? overlap/producer (first sb/reviewed-producers)))
    (is (identical? layers/producer (second sb/reviewed-producers)))
    (is (not (identical? overlap/producer (into {} overlap/producer)))
        "control: identical? distinguishes an equal copy, so the assertions
         above are about identity and not merely value")
    (is (= sb/reviewed-producers (findings/validate-producers! sb/reviewed-producers))
        "the roster is registry-valid — a malformed one fails generation")))
