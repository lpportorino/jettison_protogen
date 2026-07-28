(ns devcards.lanes-test
  "Canaries for `devcards.lanes` — the lanes protogen's own gate runs, as
   opposed to the registry machinery under them.

   Two things have to be true here, and only the first is obvious.

   THE ROUTE MUST BE PROVEN LIVE. The corpus yields ZERO findings, so a lane
   that returned [] for every card would produce byte-identical goldens, an
   identical gallery and the same `findings: 0` line. A green battery cannot
   tell a live lane from a dead one, so every canary here feeds a tree that
   MUST produce a finding.

   AND THE THING TESTED MUST BE THE THING THAT RUNS. An earlier cut asserted
   against a registry helper and a producer in isolation, on the theory that
   they stood for the call sites. A review reverted the production
   expressions to their defective form and the whole suite stayed green.
   So the canaries below call `lanes/atomic-findings` and
   `lanes/composition-findings` — the exact fns `devcards.core` calls, with
   the arguments it passes — and the producer-selection test asserts through
   the lane rather than about the helper."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [devcards.findings :as findings]
            [devcards.lanes :as lanes]
            [devcards.outcome :as outcome]))

(def ^:private clean-tree
  {:type "lv_obj" :coords [0 0 99 99] :children []})

(def ^:private defective-tree
  "A node the renderer flagged as clipped — one of `invariants/defect-flags`."
  {:type "lv_obj" :coords [0 0 99 99]
   :children [{:type "lv_label" :coords [0 0 49 9] :clipped true :children []}]})

(defn- judge
  "Run the DOM lane through the registry exactly as the gate does."
  [expect tree]
  (:live (findings/card-findings {:card-id "c"
                                  :tree tree
                                  :caps {:vis-px? true}
                                  :expect expect
                                  :producers [lanes/tree-producer]})))

(defn- invariants-of [fs] (set (map :invariant fs)))

;; ── the judged arm must actually judge ───────────────────────────────────

(deftest the-judged-arm-reports-a-real-defect-flag
  (testing "the ordinary lane has to FIRE on a defective tree. If it did
            not, every other assertion here would pass against a route that
            judges nothing — which is the exact hole this namespace exists
            to close."
    (is (contains? (invariants-of (judge :judged defective-tree)) :clipped)))
  (testing "CONTROL: a clean tree through the same arm is silent, so the
            assertion keys on the flag and not on the arm being reached"
    (is (empty? (judge :judged clean-tree)))))

;; ── the PRODUCTION entry points, not stand-ins for them ─────────────────
;; These call the same fns `devcards.core` calls, with the same arguments.
;; The previous canaries here pinned things that RESEMBLED the call sites —
;; the registry helper, the producer in isolation — and a review found both
;; green with the production expression reverted to its defective form.

(deftest a-nil-expect-reaches-the-judged-arm-through-the-REAL-entry-point
  (testing "kitchen sinks carry no :expect at all. Passing nil straight to
            the registry does NOT fall through to a lenient lane — the
            registry treats a nil value as ABSENT and REFUSES the call. So
            the default has to be applied before the call, and if it were
            dropped the kitchen sinks would not be judged leniently, they
            would blow up."
    (is (thrown? Exception
                 (findings/card-findings {:card-id "c"
                                          :tree defective-tree
                                          :caps {:vis-px? true}
                                          :expect nil
                                          :producers [lanes/tree-producer]}))))
  (testing "and through the entry point core.clj actually calls, a nil
            :expect is judged — the defect flag is reported"
    (is (contains? (invariants-of (lanes/atomic-findings "c" nil defective-tree))
                   :clipped)))
  (testing "CONTROL: the same entry point is silent on a clean tree, so the
            assertion above keys on the flag and not on the call succeeding"
    (is (empty? (lanes/atomic-findings "c" nil clean-tree))))
  (testing "and a DECLARED expect still routes — nil is a default, not an
            override that swallows the card's own declaration"
    (is (empty? (lanes/atomic-findings "c" :probe-defect defective-tree)))
    (is (= #{:probe-defect-absent}
           (invariants-of (lanes/atomic-findings "c" :probe-defect clean-tree))))))

(deftest the-composition-lane-runs-BOTH-producers-over-BOTH-modes
  (testing "the entry point core.clj calls must judge the DOM and every
            mode's emissions. If it dropped either producer the corpus would
            still render identically, so only a direct call can tell."
    (let [live (lanes/composition-findings
                "c" defective-tree
                {:dark {:commands [] :reports [] :events []}
                 :light {:commands [{:id "c1"}] :reports [] :events []}})]
      (testing "the DOM producer fired"
        (is (contains? (invariants-of live) :clipped)))
      (testing "the by-mode emission producer fired, and named WHICH mode"
        (is (contains? (invariants-of live) :unexpected-emission))
        (is (= :light (:mode (first (filter #(= :unexpected-emission (:invariant %))
                                            live))))))))
  (testing "CONTROL: a clean tree with nothing captured in either mode is
            silent, so the assertions above key on the inputs and not on the
            producers merely being present"
    (is (empty? (lanes/composition-findings
                 "c" clean-tree
                 {:dark {:commands [] :reports [] :events []}
                  :light {:commands [] :reports [] :events []}})))))

;; ── the inverted arm: absence of the defect is the finding ───────────────

(deftest probe-defect-INVERTS-the-verdict
  (testing "a :probe-defect cell exists to EXHIBIT a defect flag, so a tree
            carrying one is correct and passes"
    (is (empty? (judge :probe-defect defective-tree))))
  (testing "and a clean tree is the FAILURE for that cell — the inversion is
            the whole reason this cannot be the plain tree lane"
    (is (= #{:probe-defect-absent} (invariants-of (judge :probe-defect clean-tree))))))

(deftest probe-pixel-only-judges-nothing-and-DOES-NOT-say-so
  (testing "a pixel-only probe is exempt from the DOM lane by declaration.
            The arm must be selected by :expect, not reached by accident —
            so it stays silent on a tree the judged arm reports."
    (is (empty? (judge :probe-pixel-only defective-tree)))
    (is (contains? (invariants-of (judge :judged defective-tree)) :clipped)))
  (testing "THE NAME OF THIS TEST USED TO END '-and-says-so', which the code
            does not do and this canary never asserted. Pinned explicitly so
            the gap is a statement rather than an omission: the skipped arm's
            output is INDISTINGUISHABLE from a clean judged card's, so no
            consumer of this lane's findings can tell the two apart. The
            declaration that makes the skip legitimate lives in
            corpus/spec.edn (:expect-legend + each card's :notes), and the
            cards' pixels are pinned by gates/golden-drift-findings — see
            `lanes/tree-producer`. Emitting a third answer here is a CODE
            change with a finding-shape decision behind it, deliberately not
            made under a claims-correction pass."
    (is (= (judge :probe-pixel-only defective-tree)
           (judge :judged clean-tree))
        "the skip and a clean full judgement return the SAME value — which is
         precisely why the run cannot report the difference")))

;; ── truncation outranks the router ───────────────────────────────────────

(def ^:private truncated-tree
  "Exactly what `devcards.host/dump-tree!` substitutes when it sees the
   renderer's overflow sentinel: the real tree is GONE, not merely flagged."
  {:truncated true :children []})

(deftest truncation-fires-on-EVERY-arm-including-the-short-circuiting-ones
  (testing "the judged arm reports it — the baseline the other arms must match"
    (is (= #{:dump-truncated} (invariants-of (judge :judged truncated-tree)))))
  (testing ":probe-pixel-only returns [] for a card it declines to judge, so
            without the pre-router check a truncated dump on one of those
            cards is byte-identical to a clean one. It is the arm that most
            needs this and the one that could least report it."
    (is (= #{:dump-truncated} (invariants-of (judge :probe-pixel-only truncated-tree)))))
  (testing ":probe-defect reads flags off a tree truncation has already
            emptied, so routing first would answer :probe-defect-absent —
            red, but naming a clause that never ran. The truncation finding
            must REPLACE that diagnosis, not sit beside it."
    (let [fs (invariants-of (judge :probe-defect truncated-tree))]
      (is (= #{:dump-truncated} fs))
      (is (not (contains? fs :probe-defect-absent)))))
  (testing "and the check is not a blanket short-circuit: an UNtruncated tree
            still reaches whichever arm :expect selects. Without this control
            a producer hard-wired to report :dump-truncated would pass every
            assertion above."
    (is (empty? (judge :probe-pixel-only defective-tree)))
    (is (= #{:probe-defect-absent} (invariants-of (judge :probe-defect clean-tree))))))

;; ── producer selection is by NAME ────────────────────────────────────────

(deftest a-builtin-producer-is-selected-by-id-not-position
  (testing "the helper resolves by name and throws on an unknown id rather
            than resolving to whatever sits at that index"
    (is (= :tree (:id (findings/builtin-producer :tree))))
    (is (= :emission (:id (findings/builtin-producer :emission))))
    (is (thrown? Exception (findings/builtin-producer :no-such-lane))))
  (testing "and the COMPOSITION LANE is pinned to the rule it means, not to
            a vector index. This is the assertion that catches the actual
            regression: `(first builtin-producers)` resolves to :tree today,
            so a test of the helper alone stays green while the production
            call site has been reverted. Growing the builtin vector at the
            front must not silently repoint the lane."
    (with-redefs [findings/builtin-producers
                  (into [{:id :decoy :fn (fn [_] []) :requires #{}}]
                        findings/builtin-producers)]
      (is (= :tree (:id (findings/builtin-producer :tree))))
      (testing "the lane still judges the DOM with the decoy in front —
                a positional selection would pick the decoy and go silent"
        (is (contains? (invariants-of
                        (lanes/composition-findings
                         "c" defective-tree
                         {:dark {:commands [] :reports [] :events []}}))
                       :clipped))))))

;; ── the verdict policy this gate runs under ─────────────────────────────

(deftest this-gate-runs-the-SHIPPED-policy-unnarrowed
  (testing "protogen authors the default, so a private relaxation here would
            gate this repo more loosely than every consumer that inherits it"
    (is (= outcome/default-policy lanes/verdict-policy)))
  (testing "and under it the lanes' own findings block THROUGH THE EXPRESSION
            core.clj RUNS, which is what makes the equality above load-bearing
            rather than decorative. Asserting `outcome/exit-code` here proved
            nothing: that fn has no production caller, so forcing :exit 0 in
            the gate's own computation left this green.
            REVERT-TO-BREAK: `:exit 0` in `lanes/run-verdict`."
    (let [live (lanes/atomic-findings "c" nil defective-tree)]
      (is (seq live))
      (is (= 1 (:exit (lanes/run-verdict live))))))
  (testing "CONTROL: a clean run through the SAME fn exits zero, so the one
            above keys on the finding and not on the fn being constant"
    (is (zero? (:exit (lanes/run-verdict []))))))

(deftest run-verdict-IS-the-expression-core-runs
  (testing "core.clj cannot load under the :test alias, so for as long as the
            verdict->lines->exit computation lived in its `-main` it was the
            one expression here no canary could name — and canaries were
            written against `outcome/exit-code`, a fn with ZERO production
            callers, which stayed green through a mutation of what the gate
            actually ran. This fn is now that whole computation: after each
            core arm calls it, only `doseq println` and `System/exit` remain."
    (let [live (lanes/atomic-findings "c" nil defective-tree)
          {:keys [lines exit blocking]} (lanes/run-verdict live)]
      (is (= 1 exit))
      (is (= (vec live) (vec blocking)))
      (testing "the report core prints is computed here too — the policy line,
                the counts, the by-lane tally and the findings themselves"
        (is (some #(re-find #"verdict policy: " %) lines))
        (is (some #(re-find #"^findings: 1 " %) lines))
        (is (some #(re-find #"^by lane: " %) lines))
        (is (some #(re-find #":clipped" %) lines)))))
  (testing "and the console truncation is here rather than in core, so the
            '40 shown, remainder counted' claim can be measured"
    (let [many (vec (repeat 45 {:card "c" :invariant :clipped}))
          lines (:lines (lanes/run-verdict many))]
      (is (= 40 (count (filter #(re-find #"^\{:card " %) lines))))
      (is (some #(re-find #"^… 5 more" %) lines))))
  (testing "CONTROL: at 40 findings nothing is elided, so the line above is a
            measurement of the threshold and not boilerplate"
    (let [lines (:lines (lanes/run-verdict
                         (vec (repeat 40 {:card "c" :invariant :clipped}))))]
      (is (= 40 (count (filter #(re-find #"^\{:card " %) lines))))
      (is (not-any? #(re-find #"more" %) lines))))
  (testing "and the batch arity still blocks a disk finding without pretending
            gallery ran the per-card producer set"
    (let [finding {:gate :disk-audit
                   :card "docs"
                   :invariant :orphaned-artifact}
          v (lanes/run-verdict [finding] [])]
      (is (= 1 (:exit v)))
      (is (= [finding] (:blocking v)))
      (is (= #{} (:emittable v))))))

;; ── the CALL SITES, pinned as source text ────────────────────────────────

(def ^:private core-source
  "`devcards.core` as TEXT. It cannot be required here — it drags
   `devcards.fixtures` and the generated bindings, neither on the :test
   alias's path — so text is the only instrument that reaches it. The
   suite already reads files this way (see `lvgl-classes-test`)."
  (slurp (io/file "src/devcards/core.clj")))

(def ^:private generate-verdict-call-site
  "The generate arm's ENTIRE decision, as one form. Whitespace-tolerant and
   otherwise exact: the token sequence is the claim."
  (re-pattern
   (str "\\(let\\s+\\[\\{:keys\\s+\\[lines\\s+exit\\]\\}"
        "\\s+\\(lanes/run-verdict\\s+all-findings\\)\\]"
        "\\s+\\(doseq\\s+\\[l\\s+lines\\]\\s+\\(println\\s+l\\)\\)"
        "\\s+\\(System/exit\\s+exit\\)\\)")))

(def ^:private gallery-verdict-call-site
  "The gallery arm's ENTIRE decision. The explicit [] is the honest producer
   scope: disk reconciliation is batch-level, not a registered per-card lane."
  (re-pattern
   (str "\\(let\\s+\\[\\{:keys\\s+\\[lines\\s+exit\\]\\}"
        "\\s+\\(lanes/run-verdict\\s+audit-findings\\s+\\[\\]\\)\\]"
        "\\s+\\(doseq\\s+\\[l\\s+lines\\]\\s+\\(println\\s+l\\)\\)"
        "\\s+\\(System/exit\\s+exit\\)\\)")))

(def ^:private generate-audit-call-site
  "Both generate audits, and both insertions into the vector sent to the
   verdict. This is the production expression whose deletion was invisible
   to the donated suite."
  (re-pattern
   (str "(?s)goldens-audit\\s+"
        "\\(docgen/run-audit\\s+\"goldens\"\\s+"
        "\\(map\\s+first\\s+manifest-writes\\)\\s+"
        "\\(:goldens\\s+audit-details\\)\\)\\s+"
        "composition-audit\\s+"
        "\\(docgen/run-audit\\s+composition-out-dir\\s+"
        "\\(:artifacts\\s+comp-run\\)\\s+"
        "\\(:composition\\s+audit-details\\)\\)\\s+"
        "all-findings\\s+\\(->\\s+\\(vec\\s+findings\\).*?"
        "\\(into\\s+\\(:findings\\s+goldens-audit\\)\\)\\s+"
        "\\(into\\s+\\(:findings\\s+composition-audit\\)\\)\\)\\]"
        "\\s+\\(persist-findings!\\s+all-findings\\)")))

(def ^:private gallery-audit-call-site
  "Gallery derives its findings from the writer returns and persists them
   before reaching the verdict."
  (re-pattern
   (str "(?s)docs-audit\\s+"
        "\\(docgen/run-audit\\s+docs/audit-root\\s+"
        "\\(map\\s+:path\\s+files\\)\\s+"
        "\\(:docs\\s+audit-details\\)\\)\\s+"
        "audit-findings\\s+\\(:findings\\s+docs-audit\\)\\]"
        "\\s+\\(persist-findings!\\s+audit-findings\\)")))

(deftest generate-CALLS-run-verdict-and-exits-with-what-it-returned
  (testing "the sibling canary above pins what `run-verdict` COMPUTES; it
            cannot pin that anything calls it. Measured, on this tree:
            rewriting core's `(System/exit exit)` to `(System/exit 0)` — the
            gate then permanently green on any corpus — left the whole suite
            passing, `run-verdict-IS-the-expression-core-runs` included. So
            did deleting the call outright for
            `(System/exit (if (seq all-findings) 1 0))`.

            WHAT IT PINS, AND WHAT IT DOES NOT. It pins that the form below
            appears in core's source. It cannot pin REACHABILITY: measured,
            inserting
            `(System/exit 0)` immediately BEFORE that form leaves the pattern
            intact and the whole suite green — the same permanently-green
            gate, invisible here. That residual is inherent to a source-text
            assertion, and source text is the only instrument that reaches
            core, which is the premise above. Read this as pinning the two
            named reverts and nothing wider.

            REVERT-TO-BREAK: change `(System/exit exit)` in
            `core.clj`'s generate arm to `(System/exit 0)`."
    ;; The match is reduced to a boolean BEFORE `is` sees it — otherwise a
    ;; failure pretty-prints the whole of core.clj as the actual value.
    (let [found? (some? (re-find generate-verdict-call-site core-source))]
      (is found?
          (str "core.clj's generate arm no longer reads "
               "`(let [{:keys [lines exit]} (lanes/run-verdict all-findings)] "
               "(doseq [l lines] (println l)) (System/exit exit))`. Either the "
               "verdict moved back into core — where no test can name it — or "
               "the exit code stopped being the one run-verdict returned."))))
  (testing "CONTROL: the pattern is a real discriminator, not a tautology —
            it must NOT match the same form with the exit code forced"
    (is (not (re-find generate-verdict-call-site
                      (str "(let [{:keys [lines exit]} "
                           "(lanes/run-verdict all-findings)] "
                           "(doseq [l lines] (println l)) "
                           "(System/exit 0))"))))))

(deftest gallery-CALLS-run-verdict-and-exits-with-what-it-returned
  (testing "gallery used to decide directly with `(if (seq audit-findings)
            1 0)`, a production expression no unit test could name. The exact
            call/print/exit form is pinned here.
            REVERT-TO-BREAK: force gallery's `(System/exit exit)` to
            `(System/exit 0)`."
    (is (some? (re-find gallery-verdict-call-site core-source))
        (str "core.clj's gallery arm no longer exits with the value returned "
             "by `(lanes/run-verdict audit-findings [])`")))
  (testing "CONTROL: forcing the exit code does not match"
    (is (not (re-find gallery-verdict-call-site
                      (str "(let [{:keys [lines exit]} "
                           "(lanes/run-verdict audit-findings [])] "
                           "(doseq [l lines] (println l)) "
                           "(System/exit 0))")))))
  (testing "both decision sites are enumerated: one generate and one gallery"
    (is (= 2 (count (re-seq #"\(lanes/run-verdict\s" core-source)))))
  (testing "and the findings reaching that decision come from the paths
            docs/generate! returned, then persist before the verdict"
    (is (some? (re-find gallery-audit-call-site core-source)))))

(deftest generate-INCLUDES-both-disk-audits-in-the-verdict
  (testing "testing `docgen/audit` cannot prove generate uses its findings.
            This pins the two real audit calls AND both insertions into
            all-findings.
            REVERT-TO-BREAK: drop the goldens-audit and composition-audit
            insertions from the all-findings thread."
    (is (some? (re-find generate-audit-call-site core-source))
        (str "core.clj's generate arm no longer derives both audits and "
             "includes both findings vectors in all-findings")))
  (testing "CONTROL: merely computing the audits without inserting them does
            not match"
    (is (not (re-find generate-audit-call-site
                      (str "goldens-audit "
                           "(docgen/run-audit \"goldens\" "
                           "(map first manifest-writes) "
                           "(:goldens audit-details)) "
                           "composition-audit "
                           "(docgen/run-audit composition-out-dir "
                           "(:artifacts comp-run) "
                           "(:composition audit-details)) "
                           "all-findings (-> (vec findings) "
                           "(into golden-findings))"))))))

(deftest the-armed-set-is-the-set-that-RUNS
  (testing "`armed-producers` is what scopes the NOT-EXERCISED line, so a
            hand-kept second list would let that line describe a lane the gate
            does not run. It is derived from the two vectors the lanes pass.
            REVERT-TO-BREAK: inline the producer vectors back into
            `atomic-findings` / `composition-findings`."
    (is (= (set (map :id lanes/armed-producers))
           (into (set (map :id lanes/atomic-producers))
                 (map :id lanes/composition-producers))))
    (is (contains? (set (map :id lanes/armed-producers)) :overlap)
        "the overlap lane IS armed here — see .claude/rules/devcards.md")
    (is (contains? (set (map :id lanes/armed-producers)) :deadzone)
        "the ordered DISABLED dead-zone lane is armed here"))
  (testing "and it is a vector the ARMING VALIDATOR accepts. The assertion
            above compares SETS, so it saw nothing when a plain concat carried
            `overlap/producer` from both lanes and `validate-producers!`
            refused the result with `duplicate producer ids [:overlap]` — a
            def named 'every producer this gate arms' that the arming check
            rejects is a trap for the next caller.
            REVERT-TO-BREAK: `(into atomic-producers composition-producers)`
            in `lanes/armed-producers`."
    (is (findings/validate-producers! lanes/armed-producers))
    (is (= (count lanes/armed-producers)
           (count (distinct (map :id lanes/armed-producers))))
        "armed-producers carries a duplicate :id")
    (testing "CONTROL: the shared producer is genuinely in BOTH lane vectors,
              so the dedupe above is load-bearing and not decoration"
      (is (contains? (set (map :id lanes/atomic-producers)) :overlap))
      (is (contains? (set (map :id lanes/composition-producers)) :overlap))
      (is (contains? (set (map :id lanes/atomic-producers)) :deadzone))
      (is (contains? (set (map :id lanes/composition-producers)) :deadzone))))
  (testing "and EXACTLY ONE armed producer is three-way. `opa/producer`
            declares #{:failed :cantTell} because a faded node whose
            glyph-bearing-ness the dump cannot decide is neither a violation
            nor clean; every other producer declares nothing and is therefore
            two-way. That one declaration is what puts :cantTell IN SCOPE for
            the NOT-EXERCISED line — see the verdict canary below — while
            :untested stays out of scope, which is the half of the scoping fix
            this change does not touch."
    (is (= #{:failed :cantTell}
           (outcome/emittable-outcomes lanes/armed-producers
                                       (:fail-modes lanes/verdict-policy))))
    (is (= [:opa] (mapv :id (filter :outcomes lanes/armed-producers))))
    (is (= #{:failed :cantTell}
           (:outcomes (first (filter :outcomes lanes/armed-producers)))))
    (testing "and every armed producer is still :automatic, so the whole
              armed set can set the exit code"
      (is (every? #(nil? (:test-mode %)) lanes/armed-producers)))))

;; ── the supplied thresholds and the armed rules are ONE decision ─────────

(def ^:private declared-threshold-keys
  "Every consumer-facing threshold key the ARMED producers declare, built the
   way `findings/resolve-thresholds` builds its own index — through
   `findings/threshold-key`, so a change to that encoding moves both sides
   together instead of leaving this canary asserting a stale spelling."
  (into #{}
        (for [p lanes/armed-producers [k _] (:thresholds p)]
          (findings/threshold-key (:id p) k))))

(deftest every-supplied-threshold-is-declared-by-an-ARMED-producer
  (testing "`producer-thresholds` is handed to BOTH lanes, and
            `resolve-thresholds` refuses a key the lane's own producer vector
            does not declare. So SUPPLYING a threshold and ARMING its producer
            are one step: the half-step throws on the first card and the gate
            judges nothing.

            WITHOUT THIS, THE SUITE STILL REDS — so the claim is about WHAT the
            red says, and the first draft of this docstring overstated it.
            Measured under the revert below: the resolver's ExceptionInfo does
            name the offending key, and it surfaces as ERRORs inside canaries
            about `:expect` routing, producer selection and exemption
            vocabularies — reds attributed to subjects that are not the cause,
            which abort those bodies and drop assertions the run should have
            made. What the resolver cannot say is which half was intended: arm
            the producer, or drop the key. This is a FAIL rather than an ERROR,
            in a test whose NAME is the invariant, and it prescribes both.
            REVERT-TO-BREAK: add `:palette/colors-by-mode` (or any other
            unarmed producer's key) to `lanes/producer-thresholds`."
    (is (= #{} (set/difference (set (keys lanes/producer-thresholds))
                               declared-threshold-keys))
        (str "lanes/producer-thresholds supplies a key no ARMED producer "
             "declares. Declared across the armed set: "
             (vec (sort declared-threshold-keys))
             " — arm the producer in atomic-producers/composition-producers "
             "in the SAME change, or drop the key.")))
  (testing "CONTROL: the assertion is a real discriminator, not a tautology —
            the exact key `devcards.palette` would contribute is absent from
            the declared set, so adding it to the supplied map would be caught"
    (is (seq declared-threshold-keys))
    (is (not (contains? declared-threshold-keys :palette/colors-by-mode)))
    (is (not= #{} (set/difference (conj (set (keys lanes/producer-thresholds))
                                        :palette/colors-by-mode)
                                  declared-threshold-keys))))
  (testing "and the CONSEQUENCE is a throw rather than a fallback, on the real
            resolver and with the real lane vector — which is what makes the
            set relation above load-bearing instead of stylistic"
    (is (thrown? Exception
                 (findings/resolve-thresholds
                  lanes/atomic-producers
                  (assoc lanes/producer-thresholds :palette/colors-by-mode
                         {:dark #{"#000000"} :light #{"#FFFFFF"}})))))
  (testing "CONTROL: the map the gate actually supplies resolves against BOTH
            lane vectors. One-sided coverage would let a key declared only by an
            atomic-lane producer throw on every composition card."
    (is (map? (findings/resolve-thresholds lanes/atomic-producers
                                           lanes/producer-thresholds)))
    (is (map? (findings/resolve-thresholds lanes/composition-producers
                                           lanes/producer-thresholds)))))

(deftest the-lanes-emit-findings-with-NO-ACT-axes
  (testing "the artifact-stability pin: every finding these lanes produce is
            two-way and automatic, so out/findings.edn keeps the shape every
            consumer's triage already reads.

            NOT 'every producer' any more, and the distinction is the whole
            point: `opa/producer` DECLARES :cantTell and would stamp
            `:act/outcome` onto such a finding. What keeps the artifact stable
            is that a :cantTell BLOCKS — so the shape only changes on a run
            that is already red, never quietly on a green one."
    (let [live (into (lanes/atomic-findings "c" nil defective-tree)
                     (lanes/composition-findings
                      "c" defective-tree
                      {:dark {:commands [] :reports [] :events []}
                       :light {:commands [{:id "c1"}] :reports [] :events []}}))]
      (is (seq live) "the CONTROL — an empty vector would satisfy the next
                      two assertions vacuously")
      (is (every? (fn [f] (empty? (filter #(contains? f %) outcome/axis-keys)))
                  live))
      (testing "and each is nevertheless JUDGEABLE by the verdict — every one
                satisfies the entitlement check, so the two halves cannot
                disagree about the same finding"
        (is (every? #(nil? (outcome/axis-problem %)) live))))))

(deftest the-lanes-verdict-REPORTS-before-it-decides
  (testing "the report has to survive whatever the verdict refuses: the
            counts, the policy line and the NOT-EXERCISED line are computed
            in the same total step as the exit code, so nothing between the
            persisted vector and System/exit can throw"
    (let [live (lanes/atomic-findings "c" nil defective-tree)
          v (lanes/run-verdict live)]
      (is (= 1 (:exit v)))
      (is (empty? (:malformed v)))
      (is (some #(re-find #"^findings: " %) (:lines v)))))
  (testing "and this gate's NOT-EXERCISED line names EXACTLY :cantTell, and
            names it as a MEASUREMENT rather than as a banner. The scoping
            fix's rule is that the line may only name an outcome some ARMED
            producer can actually emit; `opa/producer` declares :cantTell, so
            naming it carries a bit — 'this corpus produced no third answer' —
            and the run in which it produced one would drop the word AND
            block, because the shipped policy fails on :cantTell. :untested is
            still named by nobody, which is the half of the fix this change
            leaves exactly as it was. It is also why the UNDETERMINED
            admission must not appear: the armed set IS supplied here.
            REVERT-TO-BREAK: drop `{:producers armed-producers}` from
            `run-verdict`."
    (let [v (lanes/run-verdict (lanes/atomic-findings "c" nil defective-tree))]
      (is (= #{:failed :cantTell} (:emittable v)))
      (is (= [:cantTell] (:not-exercised v)))
      (is (some #(re-find #"NOT EXERCISED: :cantTell 0" %) (:lines v)))
      (is (not-any? #(re-find #":untested" %)
                    (filter #(re-find #"NOT EXERCISED" %) (:lines v))))))
  (testing "CONTROL: the SAME findings under a policy fed a producer that
            also declares :untested name BOTH, so the single-word line above
            is the armed set's shape and not the line having been truncated"
    (let [v (outcome/verdict (lanes/atomic-findings "c" nil defective-tree)
                             lanes/verdict-policy
                             {:producers [{:id :contrast
                                           :outcomes #{:failed :cantTell
                                                       :untested}}]})]
      (is (some #(re-find #"NOT EXERCISED: :cantTell 0, :untested 0" %)
                (:lines v))))))

;; ── the exemption vocabulary is the ARMED set's, not one lane's ──────────

(deftest the-reason-VOCABULARY-comes-from-armed-producers-not-the-lane
  (let [contrast {:id :contrast :requires #{}
                  :outcomes #{:failed :cantTell}
                  :reasons {:noise-band "the score is in the noise band"}
                  :fn (fn [_] [])}
        exemption [{:card "c" :invariant :contrast
                    :act/outcome :cantTell :act/reason :noise-band
                    :rationale "no mask emitter for this widget class yet"
                    :retires-when "the mask emitter lands"}]]
    (testing "exemption lists are GLOBAL and `card-findings` runs once per card
              PER LANE, so a reason declared by a producer in the ARMED set but
              absent from THIS lane's own vector must still be accepted. The
              redefs put `contrast` in `armed-producers` ONLY — neither lane's
              producer vector gains it — so the vocabulary can only be coming
              from the armed set.
              REVERT-TO-BREAK: drop `:armed-producers armed-producers` from
              `atomic-findings` / `composition-findings`, or read the
              vocabulary off `producers` in `card-findings`."
      (with-redefs [lanes/armed-producers (conj lanes/armed-producers contrast)
                    lanes/gate-exemptions exemption]
        (is (contains? (invariants-of (lanes/atomic-findings "c" nil defective-tree))
                       :clipped))
        (is (contains? (invariants-of
                        (lanes/composition-findings
                         "c" defective-tree
                         {:dark {:commands [] :reports [] :events []}}))
                       :clipped))))
    (testing "CONTROL: the SAME exemption with the armed set UNCHANGED — where
              nothing anywhere declares :noise-band — throws on BOTH lanes. So
              the pass above is the armed set doing the work, and the door is
              still shut on a reason no producer declares at all"
      (with-redefs [lanes/gate-exemptions exemption]
        (is (thrown? Exception (lanes/atomic-findings "c" nil defective-tree)))
        (is (thrown? Exception
                     (lanes/composition-findings
                      "c" defective-tree
                      {:dark {:commands [] :reports [] :events []}})))))
    (testing "and the armed set the lanes hand over IS `armed-producers` — the
              vector derived from what the lanes actually pass, so the
              vocabulary cannot drift from the rules that run"
      (is (= (set (map :id lanes/armed-producers))
             (into (set (map :id lanes/atomic-producers))
                   (map :id lanes/composition-producers)))))
    (testing "and this gate's own exemption list is EMPTY — a CLAIM the gate
              has to keep making. An entry landing here unnoticed is how a
              gate quietly stops being one."
      (is (= [] lanes/gate-exemptions)))))
