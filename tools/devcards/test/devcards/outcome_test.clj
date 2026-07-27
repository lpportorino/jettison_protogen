(ns devcards.outcome-test
  "Canaries for the ACT/EARL verdict vocabulary, the run-level policy and the
   TOTAL report (`devcards.outcome`).

   Three obligations, and the first is the one that would be easy to skip.

   THE PRE-POLICY BEHAVIOUR IS PINNED BIT-FOR-BIT. protogen is the pinned
   upstream for a consumer fleet, so a finding that declares no outcome must
   set the exit code exactly as it did before this vocabulary existed. Every
   assertion below that feeds an outcome-free vector is testing that, not the
   new axis.

   AN EMPTY BLOCKING SET MUST SAY WHY IT IS EMPTY. `(is (zero? (exit-code
   …)))` passes when the policy was ignored, when nothing was classified, and
   when the finding was correctly non-blocking. So every zero here is paired
   with a control on the SAME input class that must be one.

   AND EVERY CLAUSE HERE HAS BEEN OBSERVED RED. Each deftest names, in its
   own body, the production expression whose reversion breaks it. Three
   clauses in the first draft of this file could not be broken that way and
   are repaired below rather than counted: the vocabulary check that was
   implied by the declaration check, the non-empty-set check that was implied
   by the floor check, and a closed key set that had no canary at all."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.outcome :as outcome]))

(def ^:private legacy
  "A finding in the shape every producer emits today: no ACT axes at all."
  {:card "c" :invariant :clipped :node "lv_label#1" :detail "d"})

(defn- act
  "A finding that IS entitled to ACT axes — i.e. one the producer registry
   stamped. `:producer` is that stamp; see `outcome/axis-problem`."
  [& {:as axes}]
  (merge {:card "c" :invariant :contrast :producer :contrast} axes))

(defn- narrowed
  "A policy that stops blocking on everything but a definite defect. It is
   the ONE deviation the floors still permit, and it owes its proof."
  [& {:as extra}]
  (merge {:fail-outcomes #{:failed}
          :fail-modes #{:automatic}
          :rationale "the contrast lane is arming against a fresh corpus"
          :retires-when "the corpus is clean under the shipped default"}
         extra))

(defn- msg
  "The message of whatever `f` throws, or nil. `thrown?` alone cannot tell a
   clause from its neighbour — which is how three decorative canaries got
   this far — so the clauses below assert the message."
  [f]
  (try (f) nil (catch Throwable t (ex-message t))))

(def ^:private legacy-only-producers
  "The armed set THIS repo actually has: producers declaring no `:outcomes`,
   which the registry reads as `legacy-outcomes` — :failed and nothing else.
   Only the two keys `emittable-outcomes` reads are given, because those are
   the only two it may read."
  [{:id :tree} {:id :overlap}])

(def ^:private three-way-producers
  "A consumer's armed set that CAN emit the two other blocking outcomes, so
   the NOT-EXERCISED line has something in scope to be silent about."
  [{:id :contrast :outcomes #{:failed :cantTell}}
   {:id :mask :outcomes #{:failed :untested}}])

;; ── the compatibility pin ────────────────────────────────────────────────

(deftest an-outcome-free-vector-exits-exactly-as-it-did-before
  (testing "the pre-policy expression was (if (seq all-findings) 1 0). A
            finding that declares nothing must still block, or every consumer
            repo's gate silently loosens on its next pin bump.
            REVERT-TO-BREAK: `default-outcome` to :passed."
    (is (zero? (outcome/exit-code [] outcome/default-policy)))
    (is (= 1 (outcome/exit-code [legacy] outcome/default-policy)))
    (is (= 1 (outcome/exit-code [legacy (assoc legacy :invariant :offscreen)]
                                outcome/default-policy))))
  (testing "and the two non-registry populations — the corpus gates and the
            interaction lane carry :gate with no :invariant — are counted the
            same way, since they join the terminal vector directly"
    (is (= 1 (outcome/exit-code [{:gate :corpus-secret :card "c" :detail "d"}]
                                outcome/default-policy))))
  (testing "the zero above is the EMPTY vector, not a policy that judged
            nothing: the same policy over the same finding shape is one"
    (is (empty? (outcome/blocking [] outcome/default-policy)))
    (is (= [legacy] (outcome/blocking [legacy] outcome/default-policy)))))

(deftest the-UNNAMESPACED-words-are-opaque-payload
  (testing "the axes are :act/* precisely so that a consumer producer using
            the three plainest words in the vocabulary for its own payload is
            untouched — the finding map was OPEN before these axes existed
            and taking those names would have been a migration.
            REVERT-TO-BREAK: read `:outcome`/`:test-mode` in `finding-outcome`
            / `finding-mode`."
    (let [payload (assoc legacy :outcome "suppressed by hand"
                         :test-mode :manual :reason "because")]
      (is (nil? (outcome/axis-problem payload)))
      (is (= :failed (outcome/finding-outcome payload)))
      (is (= :automatic (outcome/finding-mode payload)))
      (is (= 1 (outcome/exit-code [payload] outcome/default-policy)))))
  (testing "CONTROL: the NAMESPACED spelling of the same two values does move
            the verdict, so the zero-effect above is the namespace and not an
            axis that stopped being read"
    (is (zero? (outcome/exit-code [(act :act/test-mode :manual)]
                                  outcome/default-policy)))))

;; ── cantTell blocks by default, and stops blocking only by proof ─────────

(deftest an-all-cantTell-run-BLOCKS-under-the-shipped-default
  (testing "IBM Equal Access's disposition, not Android ATF's. A needs-review
            result that does not block is the silent skip dressed as policy:
            the finding sits in out/findings.edn and the run goes green.
            REVERT-TO-BREAK: drop :cantTell from `default-fail-outcomes`."
    (let [fs [(act :act/outcome :cantTell :act/reason :noise-band)]]
      (is (= 1 (outcome/exit-code fs outcome/default-policy)))
      (is (= fs (outcome/blocking fs outcome/default-policy))))))

(deftest a-narrowed-policy-makes-cantTell-advisory
  (testing "blocking or advisory is a CONFIGURATION, so a consumer arming a
            three-way lane against a fresh corpus is not forced to choose
            between a red run and deleting the rule"
    (let [fs [(act :act/outcome :cantTell :act/reason :noise-band)]]
      (is (zero? (outcome/exit-code fs (narrowed))))
      (is (empty? (outcome/blocking fs (narrowed))))))
  (testing "CONTROL: the same narrowed policy still blocks on a definite
            defect, so the zero above is the outcome axis and not a policy
            that stopped reading its input"
    (is (= 1 (outcome/exit-code [legacy] (narrowed))))))

(deftest an-unjudged-element-blocks-too
  (testing "'an unjudged element is a FINDING, never a skip' in machine form.
            :untested is the third non-answer — no mask was supplied, no lane
            ran — and it may not read as a pass. This is also PDL's 'an empty
            input set for a clause that must have inputs is a hard failure'
            in the one place this system can express it.
            REVERT-TO-BREAK: drop :untested from `default-fail-outcomes`."
    (is (= 1 (outcome/exit-code
              [(act :act/outcome :untested :act/reason :no-mask)]
              outcome/default-policy))))
  (testing "CONTROL: :inapplicable does NOT block, so the default set is a
            real selection rather than 'everything blocks'"
    (is (zero? (outcome/exit-code
                [(act :act/outcome :inapplicable :act/reason :no-ink)]
                outcome/default-policy)))))

;; ── :passed on a finding is a category error, refused at BOTH layers ─────

(deftest a-finding-may-NEVER-carry-passed
  (testing "the cheapest laundering path this design had: +1 keyword in
            :outcomes and +1 on the finding bought a non-blocking real
            defect, owing no reason, no doc, no :rationale, no :retires-when
            and no policy edit. A reported finding is by construction not a
            pass — this vector has no per-target result model — so :passed on
            one can only mean 'suppressed'.
            REVERT-TO-BREAK: empty `unreportable-outcomes`."
    (let [laundered (act :act/outcome :passed
                         :detail "two interactive elements SHARE pixels")]
      (is (str/includes? (str (msg #(outcome/blocking [laundered]
                                                      outcome/default-policy)))
                         "can only mean 'suppressed'"))
      (testing "and the TOTAL path does not swallow it either: it reports the
                finding as blocking rather than as a zero-count pass"
        (let [v (outcome/verdict [laundered] outcome/default-policy)]
          (is (= 1 (:exit v)))
          (is (= [laundered] (:blocking v)))
          (is (= 1 (count (:malformed v))))))))
  (testing "CONTROL: :inapplicable — the other non-blocking outcome — passes
            the same path and does NOT block, so the throw above keys on
            :passed and not on 'anything non-blocking'"
    (let [ok (act :act/outcome :inapplicable :act/reason :no-ink)]
      (is (nil? (outcome/axis-problem ok)))
      (is (zero? (:exit (outcome/verdict [ok] outcome/default-policy)))))))

;; ── the mode axis keeps a non-reproducible lane out of the verdict ───────

(deftest a-manual-finding-is-reported-but-never-blocks
  (testing "a non-reproducible lane rides the same vector and is exemptible on
            the same terms, but every other lane in this standard IS
            reproducible. Mandatory to RUN and to DISPOSITION is the whole
            obligation; a pass/fail is not part of it. The mode below is what
            the registry stamps for a producer declaring `:test-mode :manual`
            — no protogen producer does, and the hand-emitted VLM review
            reaches this filter as :automatic (see `default-fail-modes`).
            REVERT-TO-BREAK: widen `default-fail-modes` to `test-modes`."
    (let [vlm (act :invariant :legibility-doubt :act/outcome :cantTell
                   :act/reason :not-a-validated-classifier
                   :act/test-mode :manual)]
      (is (zero? (outcome/exit-code [vlm] outcome/default-policy)))
      (is (empty? (outcome/blocking [vlm] outcome/default-policy)))
      (testing "CONTROL: the identical finding at :automatic DOES block, so
                the zero keys on the mode and not on the outcome"
        (is (= 1 (outcome/exit-code [(assoc vlm :act/test-mode :automatic)]
                                    outcome/default-policy)))))))

;; ── the two populations that never pass a producer ───────────────────────

(deftest a-typod-axis-throws-even-on-a-finding-no-producer-made
  (testing "the corpus gates and the interaction lane emit straight into the
            terminal vector without ever meeting the registry, so this is the
            only place their axes can be checked at all.
            REVERT-TO-BREAK: return nil from the two vocabulary branches of
            `axis-problem`."
    (is (str/includes?
         (str (msg #(outcome/blocking [(act :act/outcome :cantTel)]
                                      outcome/default-policy)))
         "not one of [:cantTell :failed :inapplicable :passed :untested]"))
    (is (str/includes?
         (str (msg #(outcome/blocking [(act :act/test-mode :auto)]
                                      outcome/default-policy)))
         "not one of [:automatic :manual :semiAuto]")))
  (testing "CONTROL: the same gate finding with a valid outcome passes
            through and blocks, so the throw keys on the value"
    (is (= 1 (outcome/exit-code [{:gate :corpus-secret :card "c"
                                  :producer :x :act/outcome :failed}]
                                outcome/default-policy)))))

(deftest an-axis-on-a-finding-that-never-met-the-REGISTRY-is-refused
  (testing "checking the VALUE says nothing about the ENTITLEMENT to carry
            the key. The mode is 'declared PER PRODUCER, never per finding'
            and the registry enforces that — but only for registry-routed
            findings, and the populations that most need policing are exactly
            the ones that bypass it. A hand-built gate finding could
            otherwise have stamped itself :manual and walked out of the
            verdict, which is the same silent skip the mode axis exists to
            prevent, entered by the other door.
            REVERT-TO-BREAK: delete the :producer branch of `axis-problem`."
    (let [smuggled {:gate :corpus-secret :card "c" :act/test-mode :manual}]
      (is (str/includes? (str (outcome/axis-problem smuggled))
                         "not entitled to name its own outcome or mode"))
      (is (= 1 (:exit (outcome/verdict [smuggled] outcome/default-policy))))))
  (testing "CONTROL: the identical axis on a REGISTRY-stamped finding is
            accepted and does take it out of the verdict, so the refusal keys
            on the missing :producer and not on the mode value"
    (is (nil? (outcome/axis-problem (act :act/test-mode :manual))))
    (is (zero? (:exit (outcome/verdict [(act :act/test-mode :manual)]
                                       outcome/default-policy)))))
  (testing "and an ACT-axis-free finding needs no :producer at all — that is
            every corpus-gate finding in this repo"
    (is (nil? (outcome/axis-problem {:gate :corpus-secret :card "c"})))))

;; ── the report survives what the verdict refuses ─────────────────────────

(deftest the-report-SURVIVES-a-malformed-finding
  (testing "the Lighthouse hazard, relocated. The reporting path used to
            compute the blocking set FIRST, so one typo'd axis on a gate
            finding threw before the policy line, before the counts, before
            the dump and before the exit call — deleting the whole report and
            the process's verdict with it, on exactly the population the
            checking exists for. `verdict` is TOTAL instead.
            REVERT-TO-BREAK: make `verdict` call `blocking` rather than
            partitioning on `axis-problem`."
    (let [v (outcome/verdict [legacy (act :act/outcome :cantTel)]
                             outcome/default-policy)]
      (is (= 1 (:exit v)))
      (is (= 2 (count (:blocking v))) "a finding it cannot judge BLOCKS")
      (is (= 1 (count (:malformed v))))
      (is (seq (:lines v)))
      (is (some #(str/includes? % "MALFORMED (blocking)") (:lines v)))
      (is (some #(str/includes? % "findings: 2") (:lines v)))))
  (testing "a malformed POLICY fails CLOSED and still reports, rather than
            deciding nothing"
    (let [v (outcome/verdict [legacy] {:fail-outcomes #{}})]
      (is (= 1 (:exit v)))
      (is (= [legacy] (:blocking v)))
      (is (str/includes? (first (:lines v)) "MALFORMED"))
      (is (str/includes? (first (:lines v)) "failing CLOSED"))))
  (testing "CONTROL: a well-formed run reports and exits zero through the
            same function, so the ones above key on the defect"
    (let [v (outcome/verdict [] outcome/default-policy)]
      (is (zero? (:exit v)))
      (is (empty? (:malformed v)))
      (is (seq (:lines v))))))

(deftest a-malformed-policy-fails-closed-ON-AN-EMPTY-CORPUS-TOO
  (testing "THE CROSSED CELL, and it was the operational default. Fail-closed
            was implemented as 'every finding blocks', which over the EMPTY
            vector — the state every green run produces — is ZERO blocking
            findings. So a one-character consumer typo printed 'failing
            CLOSED' and then exited 0, forever, on every clean corpus: a
            permanently green gate that announced its own severity. The
            neighbouring canary could not see it because it asserts a
            NON-EMPTY vector (the one cell where 'every finding' is not zero)
            and its control pairs [] with a WELL-FORMED policy.
            REVERT-TO-BREAK: `:exit (if (seq blocked) 1 0)` in `verdict`."
    (let [v (outcome/verdict [] {:fail-outcome #{:failed}})]
      (is (= 1 (:exit v)) "an unreadable policy may not read as a pass")
      (is (str/includes? (first (:lines v)) "failing CLOSED"))
      (is (empty? (:blocking v))
          "and it fails on the POLICY, not by pretending there were findings
           — the count in the report stays honest")))
  (testing "the same one-character typo through every accessor, since a
            consumer reaches this by whichever one it happened to call"
    (is (= 1 (outcome/exit-code [] {:fail-outcome #{:failed}})))
    (is (= 1 (outcome/exit-code [] {:fail-outcomes #{}})))
    (is (= 1 (outcome/exit-code [] {:fail-outcomes #{:failed}}))
        "a policy that is merely INCOMPLETE (no :fail-modes) is malformed too"))
  (testing "CONTROL: the shipped default over the SAME empty vector exits 0,
            so the ones above key on the policy being unreadable and not on
            the emptiness"
    (is (zero? (outcome/exit-code [] outcome/default-policy)))))

(deftest exit-code-is-TOTAL-as-its-docstring-claims
  (testing "it delegated to the THROWING `blocking` while claiming 'every
            input maps to 0 or 1 and no branch declines to answer'. Two fns
            in one ns returned OPPOSITE verdicts on identical input and the
            docstring of the one a consumer would reach for described the
            other. It is a projection of `verdict` now, so the report and the
            exit code cannot disagree about the same run.
            REVERT-TO-BREAK: `(if (seq (blocking findings policy)) 1 0)`."
    (doseq [[label fs policy]
            [["a typo'd outcome" [(act :act/outcome :cantTel)]
              outcome/default-policy]
             ["an unentitled axis" [{:gate :g :card "c" :act/test-mode :manual}]
              outcome/default-policy]
             ["a laundered :passed" [(act :act/outcome :passed)]
              outcome/default-policy]
             ["an empty fail set" [legacy] {:fail-outcomes #{}}]
             ["a typo'd policy KEY" [] {:fail-outcome #{:failed}}]
             ["a non-set fail set" [legacy] (narrowed :fail-outcomes '(:failed))]]
            :let [answer (msg #(outcome/exit-code fs policy))]]
      (is (nil? answer) (str label " must not throw out of a TOTAL fn"))
      (is (= 1 (outcome/exit-code fs policy))
          (str label " must fail closed"))
      (is (= (:exit (outcome/verdict fs policy)) (outcome/exit-code fs policy))
          (str label ": the two accessors may never disagree"))))
  (testing "CONTROL: `blocking` is still the STRICT entry point and still
            throws on the same inputs, so the totality above is exit-code's
            own property and not the checking having been deleted"
    (is (some? (msg #(outcome/blocking [(act :act/outcome :cantTel)]
                                       outcome/default-policy))))
    (is (some? (msg #(outcome/blocking [] {:fail-outcome #{:failed}}))))))

(deftest the-counts-carry-their-ZEROES-and-name-what-was-not-exercised
  (testing "a `frequencies` map omits the keys nothing landed on, so '0
            cantTell observed' and 'cantTell is not a value this run could
            produce' print identically. A clause whose 'nothing to report'
            output is indistinguishable from its 'all clear' output is not
            evidence.
            REVERT-TO-BREAK: replace `tally` with plain `frequencies`."
    (let [v (outcome/verdict [legacy] outcome/default-policy)]
      (is (= {:passed 0 :failed 1 :cantTell 0 :inapplicable 0 :untested 0}
             (:by-outcome v)))
      (is (= {:automatic 1 :manual 0 :semiAuto 0} (:by-mode v)))))
  (testing "and the blocking outcomes an ARMED producer can emit but this run
            never produced are NAMED, with their count, in a line of their
            own — inferred-from-silence is the thing being refused.
            REVERT-TO-BREAK: delete the :not-exercised cond-> branch."
    (let [v (outcome/verdict [legacy] outcome/default-policy
                             {:producers three-way-producers})]
      (is (= [:cantTell :untested] (:not-exercised v)))
      (is (some #(and (str/includes? % "NOT EXERCISED")
                      (str/includes? % ":cantTell 0")
                      (str/includes? % ":untested 0"))
                (:lines v)))))
  (testing "an EMPTY findings vector gets its own NOT-EXERCISED line rather
            than an implicit pass"
    (let [v (outcome/verdict [] outcome/default-policy)]
      (is (some #(str/includes? % "0 findings judged") (:lines v)))))
  (testing "CONTROL: a run that DID exercise every blocking outcome prints no
            NOT-EXERCISED list, so the line above is a measurement and not
            boilerplate"
    (let [v (outcome/verdict [legacy
                              (act :act/outcome :cantTell
                                   :act/reason :noise-band)
                              (act :act/outcome :untested
                                   :act/reason :no-mask)]
                             outcome/default-policy
                             {:producers three-way-producers})]
      (is (empty? (:not-exercised v)))
      (is (not-any? #(str/includes? % "NOT EXERCISED") (:lines v))))))

;; ── NOT EXERCISED has to be a measurement, not a permanent banner ────────

(deftest NOT-EXERCISED-names-only-what-the-ARMED-SET-CAN-EMIT
  (testing "computed from the POLICY alone it restated its own hazard. The
            shipped policy blocks on :cantTell and :untested; every producer
            protogen arms declares :outcomes nowhere, so the registry lets it
            emit :failed and nothing else. The line therefore named
            [:cantTell :untested] on EVERY run — red, green, forever — which
            made the ONE state it exists to flag (a producer that DECLARED
            :cantTell and emitted none) byte-identical to the permanent
            baseline. A warning that fires identically on every possible run
            carries zero bits.
            REVERT-TO-BREAK: drop the `(contains? emittable o)` conjunct."
    (let [v (outcome/verdict [legacy] outcome/default-policy
                             {:producers legacy-only-producers})]
      (is (empty? (:not-exercised v))
          "nothing armed can emit :cantTell or :untested, so they are out of
           scope — not 'not exercised'")
      (is (not-any? #(str/includes? % "NOT EXERCISED") (:lines v)))
      (is (= #{:failed} (:emittable v)))))
  (testing "CONTROL: arm a producer that DECLARES :cantTell over the same
            findings and the same policy, and the line appears naming exactly
            that clause. This is the distinction the whole fix is for: 'a
            clause that CAN fire and did not' now reads differently from 'an
            outcome nothing in the armed set can emit'"
    (let [contrast-only [{:id :contrast :outcomes #{:failed :cantTell}}]
          v (outcome/verdict [legacy] outcome/default-policy
                             {:producers contrast-only})
          line (first (filter #(str/includes? % "NOT EXERCISED") (:lines v)))]
      (is (= [:cantTell] (:not-exercised v)))
      (is (str/includes? line ":cantTell 0"))
      (is (not (str/includes? line ":untested"))
          ":untested is still out of scope — the line narrowed to the clause
           that was actually armed, it did not merely become non-empty.
           Asserted on THE LINE, because the by-outcome tally two lines up
           carries ':untested 0' on every run and a search over all lines
           would have passed for that reason instead")
      (testing "and the list tracks the RUN: emit the :cantTell and that
                clause leaves the list, while :failed — which the same
                producer declares and this run did not produce — takes its
                place. Both directions move, so it is a measurement"
        (let [v2 (outcome/verdict [(act :act/outcome :cantTell
                                        :act/reason :noise-band)]
                                  outcome/default-policy
                                  {:producers contrast-only})]
          (is (= [:failed] (:not-exercised v2))))))))

(deftest a-MANUAL-producers-outcomes-are-out-of-scope-for-a-BLOCKING-line
  (testing "a producer outside the policy's fail-modes cannot contribute a
            blocking finding at all, so its declared :cantTell is not an
            unexercised BLOCKING clause — it is a clause that could never
            block. Without the mode filter a VLM-only lane would have carried
            a permanent NOT-EXERCISED line of exactly the kind this pair of
            canaries exists to delete.
            REVERT-TO-BREAK: drop the fail-modes `filter` in
            `emittable-outcomes`."
    (let [vlm {:id :vlm :test-mode :manual :outcomes #{:failed :cantTell}}
          v (outcome/verdict [legacy] outcome/default-policy {:producers [vlm]})]
      (is (empty? (:emittable v)))
      (is (empty? (:not-exercised v)))
      ;; …and the run must SAY that, or the emptiness above is a silence
      ;; rather than a measurement. An earlier version of this test asserted
      ;; only the two emptinesses, which certified the hole: a VLM-only armed
      ;; set printed a report byte-identical to a fully-exercised
      ;; deterministic gate.
      ;; REVERT-TO-BREAK: delete the (and emittable (empty? emittable))
      ;; cond-> branch in outcome/verdict.
      (is (some #(str/includes? % "NOTHING IN SCOPE") (:lines v))
          "an armed set that can block on nothing must announce it")))
  (testing "CONTROL: the IDENTICAL producer at the default (:automatic) mode
            does put :cantTell in scope, so the emptiness above keys on the
            mode and not on the declaration being ignored"
    (let [auto {:id :vlm :outcomes #{:failed :cantTell}}
          v (outcome/verdict [legacy] outcome/default-policy {:producers [auto]})]
      (is (= #{:failed :cantTell} (:emittable v)))
      (is (= [:cantTell] (:not-exercised v))))))

(deftest an-UNSUPPLIED-armed-set-says-so-rather-than-guessing
  (testing "'an unjudged element is a FINDING, never a skip' applies to the
            report too. Without the producers the run cannot know which
            blocking outcomes were in scope, and silently printing no
            NOT-EXERCISED line would spell 'every clause fired' the same way
            as 'I could not look'.
            REVERT-TO-BREAK: delete the (nil? emittable) cond-> branch."
    (let [v (outcome/verdict [legacy] outcome/default-policy)]
      (is (nil? (:not-exercised v)) "nil is UNKNOWN; [] would be a claim")
      (is (nil? (:emittable v)))
      (is (some #(str/includes? % "NOT EXERCISED: UNDETERMINED") (:lines v)))))
  (testing "CONTROL: supplying the armed set replaces the admission with a
            measurement, so the line above is not printed unconditionally"
    (let [v (outcome/verdict [legacy] outcome/default-policy
                             {:producers legacy-only-producers})]
      (is (= [] (:not-exercised v)))
      (is (not-any? #(str/includes? % "UNDETERMINED") (:lines v))))))

(deftest a-NON-MAP-finding-is-REPORTED-not-thrown
  (testing "the totality claim has to hold over the vector as it really
            arrives, not over the shape the registry would have allowed.
            `findings/check-findings!` requires map?, but the populations this
            checking exists for — the corpus gates and the interaction lane —
            build straight into the terminal vector without meeting it, and
            `contains?` throws on a string or a keyword. A throw here lands
            AFTER out/findings.edn is written, so it deletes the whole report
            and falsifies core's 'counts always print'.

            WHERE THE THROW WOULD COME FROM, measured rather than assumed:
            `finding-outcome` and `finding-mode` are `get`-shaped, so on a
            string they return the valid defaults :failed / :automatic and the
            vocabulary branches do NOT catch a non-map. `contains?` is the only
            thing that throws, and it is reached through `carried`.
            So TOTALITY has two independently sufficient guards — the
            (not (map? f)) cond branch, which returns first, and the
            (when (map? f) …) on `carried`. Removing EITHER alone is safe;
            removing BOTH is what throws. That is belt-and-braces on purpose,
            and it means no single revert can red the totality assertion.
            The MESSAGE has only one source, so that one is a clean canary.
            REVERT-TO-BREAK (message): delete the (not (map? f)) cond branch —
            the problem string then talks about :act/outcome nil, which sends a
            reader hunting an axis the finding never carried.
            REVERT-TO-BREAK (totality): delete that branch AND drop the
            (when (map? f) …) on `carried`."
    (doseq [junk ["a string" :a-keyword 42 nil []]]
      (let [p (outcome/axis-problem junk)]
        (is (string? p)
            (str (pr-str junk) " must be reported as a problem, not throw"))
        (is (str/includes? p "not a map")
            (str (pr-str junk) " must be told WHY it is unreadable, not handed "
                 "a message about an axis it never carried")))
      (let [v (outcome/verdict [junk] outcome/default-policy)]
        (is (= 1 (:exit v)) "a finding nobody can read must block")
        (is (seq (:lines v)) "the report survives it"))))
  (testing "CONTROL: a well-formed map is still judged on its axes, so the
            branch above narrows and does not blanket-accept"
    (is (nil? (outcome/axis-problem legacy)))))

(deftest an-UNREADABLE-POLICY-does-not-report-scope-from-the-DEFAULT
  (testing "the header says the shipped default is NOT assumed. Computing
            :emittable / :not-exercised from it anyway would report a
            measurement against the very policy the run just disclaimed —
            and the two reasons the scope is unknowable must not be reported
            as one, or a reader told to 'pass :producers' when they already
            did goes looking in the wrong place.
            REVERT-TO-BREAK: drop the (when-not policy-problem …) guard on
            `emittable` in outcome/verdict."
    (let [v (outcome/verdict [] {:fail-outcome #{:failed}}
                             {:producers legacy-only-producers})]
      (is (= 1 (:exit v)) "an unreadable policy still fails closed")
      (is (nil? (:emittable v)) "scope is UNKNOWN, not the default's")
      (is (nil? (:not-exercised v)))
      (is (some #(str/includes? % "the verdict policy is unreadable") (:lines v))
          "the line must name the POLICY as the cause")
      (is (not-any? #(str/includes? % "was not supplied") (:lines v))
          "and must not blame the armed set, which was supplied")))
  (testing "CONTROL: same empty corpus, same armed set, only the policy made
            READABLE — the run yields a measurement instead of an admission,
            so the branch above is the policy's doing and not the inputs'"
    (let [v (outcome/verdict [] outcome/default-policy
                             {:producers legacy-only-producers})]
      (is (= [:failed] (:not-exercised v))
          "an outcome the armed set CAN emit, seen zero times, is the measurement")
      (is (not-any? #(str/includes? % "UNDETERMINED") (:lines v))))))

(deftest the-report-NAMES-NO-SOURCE-STANDARD
  (testing "the vocabulary is borrowed verbatim and the provenance belongs in
            the docstrings. A printed line naming a standard would claim a
            conformance no gate here measures — the same overclaim the
            hardware-scoped rules are kept out of.
            REVERT-TO-BREAK: put 'ACT Rules' in `describe-policy`'s prefix.
            The token has to be one the regex below actually names — 'W3C
            ACT' does NOT red this, because the pattern is `\\bACT Rules`."
    (let [lines (concat (outcome/report-lines [] outcome/default-policy)
                        (outcome/report-lines [legacy] (narrowed))
                        (outcome/report-lines
                         [(act :act/outcome :cantTel)] outcome/default-policy))]
      (is (seq lines))
      (is (not-any? #(re-find #"(?i)\bACT Rules|\bEARL\b|WCAG|MIL-STD|ISO 15008|compliant"
                              %)
                    lines))))
  (testing "CONTROL: the same regex DOES fire on a line that names one, so
            the assertion above is not vacuous"
    (is (re-find #"(?i)\bACT Rules|\bEARL\b|WCAG|MIL-STD|ISO 15008|compliant"
                 "verdict: WCAG AA compliant"))))

;; ── the policy's own shape ───────────────────────────────────────────────

(deftest validate-policy-refuses-a-NON-SET-fail-set-with-its-own-message
  (testing "this clause was DECORATIVE in the first draft: deleting it left
            the suite green, because an empty set trips the FLOOR clause two
            lines down. It is the only clause that produces a correct message
            for a non-set — nil and a vector fall through to the misleading
            'may not drop :failed' (true of every wrong value, so it names
            nothing) and a LIST throws a raw IllegalArgumentException out of
            `contains?`. So it is pinned by MESSAGE, on every shape.
            REVERT-TO-BREAK: delete the (and (set? v) (seq v)) block."
    (doseq [v [#{} nil [] '(:failed) [:failed] :failed]]
      (is (str/includes?
           (str (msg #(outcome/validate-policy! (narrowed :fail-outcomes v))))
           "must be a NON-EMPTY set")
          (str "fail-outcomes " (pr-str v)))
      (is (str/includes?
           (str (msg #(outcome/validate-policy! (narrowed :fail-modes v))))
           "must be a NON-EMPTY set")
          (str "fail-modes " (pr-str v)))))
  (testing "CONTROL: the floor clause still has its OWN message, so the two
            are distinguishable and neither is standing in for the other"
    (is (str/includes?
         (str (msg #(outcome/validate-policy!
                     (narrowed :fail-outcomes #{:cantTell}))))
         "may not drop :failed"))))

(deftest validate-policy-refuses-every-other-way-to-disarm-the-gate
  (testing "dropping the FLOOR is a disabled gate, not a policy: a definite
            deterministic defect must always block.
            REVERT-TO-BREAK: delete the (contains? v floor) block."
    (is (str/includes?
         (str (msg #(outcome/validate-policy!
                     (narrowed :fail-outcomes #{:cantTell}))))
         "may not drop :failed"))
    (is (str/includes?
         (str (msg #(outcome/validate-policy! (narrowed :fail-modes #{:manual}))))
         "may not drop :automatic")))
  (testing "an unknown VALUE is refused rather than ignored — the same reason
            an unknown threshold key throws"
    (is (str/includes?
         (str (msg #(outcome/validate-policy!
                     (narrowed :fail-outcomes #{:failed :bogus}))))
         "names unknown values")))
  (testing "and an outcome that is LEGAL but can never reach a finding is
            refused too — :passed is in `outcomes`, so it clears the
            unknown-values check above, but `unreportable-outcomes` keeps it
            off every finding at both layers. Naming it arms a clause that can
            never fire: config that reads as a tightening and is inert. This
            is `validate-exemptions!`'s 'stale from birth' refusal on the
            policy side.
            REVERT-TO-BREAK: delete the (filter refused v) block."
    (is (str/includes?
         (str (msg #(outcome/validate-policy!
                     (narrowed :fail-outcomes #{:failed :passed}))))
         "never reachable"))
    (testing "CONTROL: the SAME set minus :passed is accepted, so the throw
              keys on :passed and not on the narrowing itself"
      (is (outcome/validate-policy! (narrowed :fail-outcomes #{:failed})))))
  (testing "as is an unknown KEY, so a misspelt knob cannot look armed"
    (is (str/includes?
         (str (msg #(outcome/validate-policy! (narrowed :fail-outcome #{:failed}))))
         "unknown keys")))
  (testing "and a deviation owes the same proof an exemption owes"
    (is (str/includes?
         (str (msg #(outcome/validate-policy! (narrowed :rationale "  "))))
         "owes :rationale"))
    (is (str/includes?
         (str (msg #(outcome/validate-policy! (dissoc (narrowed) :retires-when))))
         "owes :retires-when")))
  (testing "CONTROL: the shipped default needs NO proof, and a fully-proven
            narrowing is accepted — so every throw above keys on its own
            clause and not on the call shape"
    (is (= outcome/default-policy
           (outcome/validate-policy! outcome/default-policy)))
    (is (outcome/validate-policy! (narrowed)))))

(deftest describe-policy-prints-a-narrowing-and-its-proof
  (testing "the least a decision to stop failing on something owes is a line
            in the log saying it was made"
    (let [line (outcome/describe-policy (narrowed))]
      (is (re-find #"NARROWED" line))
      (is (re-find #"arming against a fresh corpus" line))
      (is (re-find #"retires when" line))))
  (testing "CONTROL: the shipped default says so instead of claiming a
            narrowing, so the assertion above is not true of every input"
    (let [line (outcome/describe-policy outcome/default-policy)]
      (is (re-find #"shipped default" line))
      (is (not (re-find #"NARROWED" line))))))

(deftest the-vocabulary-is-the-standard-verbatim
  (testing "no translation table — a spelling this repo has to map onto the
            standard is a spelling that can disagree with it"
    (is (= #{:passed :failed :cantTell :inapplicable :untested} outcome/outcomes))
    (is (= #{:automatic :semiAuto :manual} outcome/test-modes)))
  (testing "and the derived sets are DERIVED, so a vocabulary edit cannot
            leave one of them behind"
    (is (= #{:cantTell :inapplicable :untested} outcome/reasoned-outcomes))
    (is (= #{:act/outcome :act/test-mode :act/reason} outcome/axis-keys))))
