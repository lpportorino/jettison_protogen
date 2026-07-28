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
            [devcards.invariants :as invariants]
            [devcards.outcome :as outcome])
  (:import (java.time LocalDate)))

(def ^:private legacy
  "A finding in the shape every producer emits today: no ACT axes at all."
  {:card "c" :invariant :clipped :node "lv_label#1" :detail "d"})

(defn- act
  "A finding that IS entitled to ACT axes — i.e. one the producer registry
   stamped. `:producer` is that stamp; see `outcome/axis-problem`."
  [& {:as axes}]
  (merge {:card "c" :invariant :contrast :producer :contrast} axes))

(defn- days-out
  "An ISO date string `n` days from the LIVE clock (negative = before).

   DERIVED FROM `outcome/today`, never written as a literal, for the reason
   `devcards.findings-test`'s waiver fixture carries: every policy below
   reaches `validate-policy!`'s short arity and is therefore judged against
   the real date, so a hardcoded `:expires` would pass until the day it did
   not and then red this whole namespace on a morning nobody touched it, for
   a reason unrelated to anything here tests. The expiry and horizon canaries
   further down pin a date instead — they can, because they call the
   explicit-date arity, and pinning is what lets them assert exact messages."
  [n]
  (str (.plusDays ^java.time.LocalDate (outcome/today) n)))

(defn- narrowed
  "A policy that stops blocking on everything but a definite defect. It is
   the ONE deviation the floors still permit, and it owes its proof — all
   FOUR keys, the same set `invariants/exemption-proof-keys` demands of a
   scoped waiver."
  [& {:as extra}]
  (merge {:fail-outcomes #{:failed}
          :fail-modes #{:automatic}
          :rationale "a contrast lane is arming against a fresh corpus"
          :retires-when "the corpus is clean under the shipped default"
          :owner "devcards-maintainer"
          :expires (days-out 30)}
         extra))

(defn- widened
  "A policy that blocks on MORE than the shipped default. The mode floor is
   the whole mode default, so this axis is the only one a widening can even
   use — which is why the direction went unnoticed long enough for the run
   header to print it as a NARROWING."
  [& {:as extra}]
  (merge (narrowed)
         {:fail-outcomes (conj outcome/default-fail-outcomes :inapplicable)}
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

(def ^:private inapplicable-producers
  "An armed set that can emit :inapplicable — the one outcome the shipped
   default does NOT block, so it is the only thing a widening can add. Without
   a producer declaring it, every widening is INERT and the widening canaries
   below would be measuring inertness rather than direction."
  [{:id :mask :outcomes #{:failed :inapplicable}}])

(def ^:private pinned-now
  "The date the expiry canaries are judged against. `validate-policy!`'s
   two-arity takes it, so those messages can be asserted EXACTLY — a canary
   written against the live clock could only assert `thrown?`, and `thrown?`
   cannot tell the expiry clause from the horizon clause that reads the same
   key."
  (LocalDate/parse "2026-01-15"))

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
  (testing "CONTROL: the shipped default needs NO proof, and a fully-proven
            narrowing is accepted — so every throw above keys on its own
            clause and not on the call shape"
    (is (= outcome/default-policy
           (outcome/validate-policy! outcome/default-policy)))
    (is (outcome/validate-policy! (narrowed)))))

;; ── the deviation owes the SAME FOUR KEYS the waiver owes ────────────────

(deftest the-proof-a-concession-owes-has-ONE-HOME
  (testing "the whole defect was two accountability shapes drifting apart, so
            the repair is worth nothing if the two sets are merely EQUAL
            today. They are the SAME OBJECT: `invariants/exemption-proof-keys`
            reads `outcome/proof-keys`, and the horizon and the clock moved
            with it, because a shared key set checked by two independent
            clause bodies is the same drift wearing a shared name.
            REVERT-TO-BREAK: re-spell either set as a literal in
            `devcards.invariants`."
    (is (identical? outcome/proof-keys invariants/exemption-proof-keys))
    (is (= #{:rationale :retires-when :owner :expires} outcome/proof-keys))
    (is (= outcome/horizon-days invariants/waiver-horizon-days)))
  (testing "the CLOCK is one clock, proven by REDEF rather than by comparing
            two live reads — which would agree by coincidence on every day but
            one, and disagree across a midnight boundary for reasons unrelated
            to delegation. Two clocks would expire a waiver and a deviation on
            different mornings.
            REVERT-TO-BREAK: give `invariants/today` its own
            `(LocalDate/now ZoneOffset/UTC)` body back."
    (with-redefs [outcome/today (constantly (LocalDate/parse "2020-06-01"))]
      (is (= (LocalDate/parse "2020-06-01") (invariants/today)))))
  (testing "and the policy's closed key set is DERIVED from it, so a key the
            validator DEMANDS can never be a key it refuses as unknown. The
            declared list in the unknown-key refusal is `policy-keys` itself,
            so a re-spelling that dropped one would show up here AND make the
            fully-proven narrowing throw.
            REVERT-TO-BREAK: re-spell `policy-keys` as the two-proof-key
            literal it was."
    (let [m (str (msg #(outcome/validate-policy! (narrowed :fail-outcome #{:failed}))))]
      (is (str/includes? m "unknown keys"))
      (doseq [k outcome/proof-keys]
        (is (str/includes? m (str k)) (str "declared: must name " k))))))

(deftest a-policy-DEVIATION-owes-the-four-keys-a-WAIVER-owes
  (testing "the GLOBAL act owed two prose strings while the SCOPED one owed
            four including an :owner and an :expires a validator can enforce.
            A `:fail-outcomes` that drops :cantTell silences a whole verdict
            class on every card at once; a waiver silences one (card,
            invariant, node, outcome, mode, reason) tuple. No reading makes
            the broader act owe the smaller proof, so the deviation came up to
            the waiver rather than the reverse.
            REVERT-TO-BREAK: delete the `(when (deviation policy)
            (check-proof! …))` form at the end of `validate-policy!`."
    (doseq [[k broken] [[:owner (narrowed :owner "   ")]
                        [:owner (dissoc (narrowed) :owner)]
                        [:rationale (narrowed :rationale "  ")]
                        [:retires-when (dissoc (narrowed) :retires-when)]]]
      (is (str/includes? (str (msg #(outcome/validate-policy! broken)))
                         (str k " must be a non-blank string"))
          (str k " " (pr-str broken)))))
  (testing "and the message says WHICH act it is refusing, so a red is
            routable back to the policy rather than to some waiver list
            (the two share one clause body and differ only in this noun).
            REVERT-TO-BREAK: pass \"waiver\" as `check-proof!`'s subject in
            `validate-policy!` — `devcards.invariants-test` stays GREEN under
            that mutation, which is exactly what makes this canary its own."
    (is (str/includes? (str (msg #(outcome/validate-policy!
                                   (dissoc (narrowed) :owner))))
                       "the proof a policy deviation owes is mandatory")))
  (testing ":expires is the fourth key and its FIRST clause is the same
            non-blank one — an entry that never tried to have a date reads
            differently from one typed wrong, and the two repairs differ.
            REVERT-TO-BREAK: delete the string/blank guard in `proof-expiry`."
    (is (str/includes? (str (msg #(outcome/validate-policy!
                                   (dissoc (narrowed) :expires))))
                       ":expires must be an ISO-8601 date STRING"))
    (is (str/includes? (str (msg #(outcome/validate-policy!
                                   (narrowed :expires (LocalDate/parse "2026-02-01")))))
                       ":expires must be an ISO-8601 date STRING")))
  (testing "and a date typed WRONG is its own clause.
            REVERT-TO-BREAK: delete the DateTimeParseException catch in
            `proof-expiry`."
    (doseq [bad ["soon" "2026-13-01" "2026-02-30" "1/1/2026" "2026-1-1"]]
      (is (str/includes? (str (msg #(outcome/validate-policy!
                                     (narrowed :expires bad))))
                         "is not an ISO-8601 date")
          bad)))
  (testing "CONTROL: the fully-proven narrowing validates and the SHIPPED
            DEFAULT owes none of the four, so every throw above keys on its
            own clause and not on 'any policy at all'"
    (is (outcome/validate-policy! (narrowed)))
    (is (= outcome/default-policy
           (outcome/validate-policy! outcome/default-policy)))
    (doseq [k outcome/proof-keys]
      (is (not (contains? outcome/default-policy k))
          (str "the shipped default must not carry " k)))))

(deftest an-EXPIRED-policy-deviation-is-a-HARD-failure-that-NAMES-ITS-OWNER
  (testing "this is what makes a global concession TEMPORARY. Before it, a
            `:fail-outcomes` narrowed once stayed narrowed forever: the only
            retirement condition was prose no machine can evaluate, so the
            decision could only be re-taken by someone remembering to look.
            REVERT-TO-BREAK: delete the `.isBefore` clause in `check-proof!`."
    (let [m (str (msg #(outcome/validate-policy!
                        (narrowed :expires "2026-01-14") pinned-now)))]
      (is (str/includes? m "policy deviation EXPIRED on 2026-01-14 (1 day ago)"))
      (testing "and it ROUTES ITSELF — the red arrives with a person on it,
                which is the entire purchase of :owner"
        (is (str/includes? m "devcards-maintainer")))
      (testing "CONTROL — ATTRIBUTION: it carries NONE of the horizon
                clause's wording. The horizon reads the SAME key and would
                refuse a bad date for its own reason, so without this the red
                above could be its neighbour's"
        (is (not (str/includes? m "at most")))
        (is (not (str/includes? m " out —"))))))
  (testing "CONTROL — THE BOUNDARY: a deviation expiring TODAY is still in
            force. One day apart, every other key byte-identical: one throws,
            one validates — so the clause keys on the DATE"
    (is (nil? (msg #(outcome/validate-policy!
                     (narrowed :expires "2026-01-15") pinned-now))))))

(deftest the-HORIZON-refuses-a-deviation-written-never-to-lapse
  (testing "the opposite mistake to expiry: `:expires \"2099-01-01\"`
            satisfies every other clause and is a permanent deviation with a
            date painted on it. The bound is read from `outcome/horizon-days`
            rather than typed, so a canary written against the constant cannot
            pass a silently widened one.
            REVERT-TO-BREAK: delete the `.isAfter` clause in `check-proof!`."
    (is (str/includes?
         (str (msg #(outcome/validate-policy!
                     (narrowed :expires (str (.plusDays pinned-now
                                                        (inc outcome/horizon-days))))
                     pinned-now)))
         (str "at most " outcome/horizon-days " days"))))
  (testing "CONTROL — THE BOUNDARY: exactly the horizon validates, so the
            throw keys on crossing it and not on being far away"
    (is (nil? (msg #(outcome/validate-policy!
                     (narrowed :expires (str (.plusDays pinned-now
                                                        outcome/horizon-days)))
                     pinned-now)))))
  (testing "CONTROL — ATTRIBUTION: the horizon message does NOT say EXPIRED.
            The two clauses read the same key and can never both fire, so each
            red must be readable back to one of them"
    (is (not (str/includes?
              (str (msg #(outcome/validate-policy!
                          (narrowed :expires (str (.plusDays pinned-now
                                                             (inc outcome/horizon-days))))
                          pinned-now)))
              "EXPIRED"))))
  (testing "and the ONE horizon governs BOTH acts. A global concession on a
            LONGER leash than a card-scoped one is the inversion this whole
            change removes, and nothing here can source a shorter number"
    (is (= 90 outcome/horizon-days))))

(deftest the-DEFAULT-CLOCK-is-live-on-the-policy-path-too
  (testing "the 1-arity — the one `verdict`, and therefore every caller in
            this repo, reaches — reads a REAL clock rather than a frozen one.
            Both inputs below are stable forever: a date in 2020 can never
            stop being past, and one day from `outcome/today` can never stop
            being future.
            REVERT-TO-BREAK: replace `(today)` in `validate-policy!`'s
            1-arity with `(LocalDate/parse \"2026-01-15\")` — the first
            assertion survives that (2020 is still past) and the SECOND fails,
            because a date one day from the real now is then far beyond the
            frozen horizon."
    (is (str/includes?
         (str (msg #(outcome/validate-policy! (narrowed :expires "2020-01-01"))))
         "policy deviation EXPIRED on 2020-01-01"))
    (is (nil? (msg #(outcome/validate-policy! (narrowed :expires (days-out 1))))))))

;; ── the RATCHET: a deviation that matches nothing ────────────────────────
;; `apply-exemptions` reports a waiver matching no finding as :stale-exemption,
;; so the waiver list can only shrink. A deviation had no analogue — and
;; "nothing to go stale against" was the defect rather than a missing
;; convenience. `demoted-findings` supplies the relation; these pin the ratchet
;; over it.

(defn- dev-run
  "`verdict` over `findings` under `policy` with `producers` armed, reduced to
   the two things every canary below reads: the exit code and the ONE
   DEVIATION line. Reading the line rather than only the colour is what makes
   :stale distinguishable from :inert — both are red, and a canary that
   asserted only non-zero could not tell which clause produced it."
  [findings policy producers]
  (let [v (outcome/verdict findings policy {:producers producers})]
    {:exit (:exit v)
     :status (:status (:deviation v))
     :line (first (filter #(str/starts-with? % "DEVIATION") (:lines v)))}))

(deftest a-STALE-deviation-FAILS-THE-RUN-on-an-EMPTY-corpus-too
  (testing "the ratchet a global concession never had. This policy stops
            blocking on :cantTell and :untested; an ARMED producer declares it
            can emit both; and the run produced NO finding the policy demoted.
            The concession bought nothing, so it is debt with no purchase and
            the run fails on it — exactly as a waiver that matched no finding
            is itself a finding.

            THE EMPTY CORPUS IS THE POINT, not a convenience. A stale
            deviation is most likely to be discovered on a clean run, which is
            precisely where a verdict inferred from a blocking COUNT reads
            zero — the same defect that once printed 'failing CLOSED' and
            exited 0.
            REVERT-TO-BREAK: drop `dev-problem` from `verdict`'s `:exit`
            expression."
    (let [r (dev-run [] (narrowed) three-way-producers)]
      (is (= :stale (:status r)))
      (is (= 1 (:exit r)))
      (is (str/includes? (str (:line r)) "DEVIATION IS STALE"))
      (is (str/includes? (str (:line r)) "[:cantTell :untested]"))))
  (testing "CONTROL: the IDENTICAL policy and armed set over a corpus that
            DOES contain a demoted finding is :live and exits 0 — so the red
            above keys on the deviation having demoted nothing, and not on the
            narrowing itself nor on the empty vector"
    (let [r (dev-run [(act :act/outcome :cantTell :act/reason :noise-band)]
                     (narrowed) three-way-producers)]
      (is (= :live (:status r)))
      (is (zero? (:exit r)))
      (is (str/includes? (str (:line r)) "demoted 1 finding(s)"))))
  (testing "CONTROL: the SHIPPED DEFAULT over the same empty vector and the
            same armed set reports no deviation at all and exits 0, so none of
            this reaches a run that did not deviate — protogen's own gate
            included (`lanes/verdict-policy` IS the default)"
    (let [r (dev-run [] outcome/default-policy three-way-producers)]
      (is (nil? (:status r)))
      (is (nil? (:line r)))
      (is (zero? (:exit r)))))
  (testing "and a MALFORMED finding does not keep a stale deviation looking
            live: it blocks under every policy, so no policy can have demoted
            it. The one below carries a :cantTell that WOULD be demoted on its
            outcome alone — it is malformed only because nothing stamped it
            :producer — so without the exclusion the deviation reads :live and
            the ratchet is disarmed by a finding it has nothing to do with.
            The exit is 1 either way (a malformed finding always blocks), so
            :status is the only assertion that can see this.
            REVERT-TO-BREAK: drop the `(nil? (axis-problem %))` conjunct from
            `demoted-findings`."
    (let [r (dev-run [{:card "c" :invariant :contrast
                       :act/outcome :cantTell :act/reason :noise-band}]
                     (narrowed) three-way-producers)]
      (is (= :stale (:status r))))))

(deftest a-deviation-NOTHING-ARMED-CAN-EMIT-is-INERT-and-says-which
  (testing "`validate-policy!` already refuses a policy naming :passed —
            'never reachable on a finding, so the clause could never fire'.
            That is decidable from the VOCABULARY. The same defect against the
            ARMED SET is not: only a run knows which producers are armed, so a
            shape check cannot see it and this is the only layer that can.
            REVERT-TO-BREAK: delete the `(not-any? emittable touched)` branch
            of `deviation-status`'s cond."
    (let [r (dev-run [] (narrowed) legacy-only-producers)]
      (is (= :inert (:status r)))
      (is (= 1 (:exit r)))
      (is (str/includes? (str (:line r)) "DEVIATION IS INERT"))
      (is (str/includes? (str (:line r)) "could not have changed a single verdict"))))
  (testing "CONTROL — ATTRIBUTION: the same policy against an armed set that
            CAN emit those outcomes is STALE, not inert. Both are red, so the
            colour alone proves nothing; the two verdicts mean different
            things and demand different repairs (remove the deviation vs. the
            deviation was never reachable), and the messages are disjoint"
    (let [r (dev-run [] (narrowed) three-way-producers)]
      (is (= :stale (:status r)))
      (is (not (str/includes? (str (:line r)) "INERT")))))
  (testing "and a WIDENING onto an unemittable outcome is inert by the same
            clause — 'reads as a tightening and is not' is direction-free"
    (let [r (dev-run [] (widened) three-way-producers)]
      (is (= :inert (:status r)))
      (is (= 1 (:exit r))))))

(deftest a-WIDENING-cannot-go-STALE-and-is-not-called-a-NARROWING
  (testing "the asymmetry is the argument, not an omission. A NARROWING that
            demoted nothing is a concession that conceded nothing. A WIDENING
            that blocked nothing extra is a guard over a clean corpus — the
            state it was written to reward — and calling that stale would
            ratchet OUT the strictest policies this validator permits.
            REVERT-TO-BREAK: drop the `(= :narrowing kind)` conjunct from
            `deviation-status`'s :stale branch."
    (let [r (dev-run [] (widened) inapplicable-producers)]
      (is (= :live (:status r)))
      (is (zero? (:exit r)))
      (is (str/includes? (str (:line r)) "WIDENED"))
      (testing "and it reports what it ADDS rather than what it demoted — a
                widening demotes 0 by definition, so printing that number
                would be the same digit on every possible widening run.
                REVERT-TO-BREAK: print the :demoted branch for both kinds."
        (is (str/includes? (str (:line r)) "blocks additionally on"))
        (is (str/includes? (str (:line r)) "[:inapplicable]"))
        (is (not (str/includes? (str (:line r)) "demoted"))))))
  (testing "CONTROL: a NARROWING over an armed set that can reach it, with the
            same empty corpus, IS stale and DOES fail — so the zero above is
            the direction and not a ratchet that stopped reading its input"
    (let [r (dev-run [] (narrowed) three-way-producers)]
      (is (= :stale (:status r)))
      (is (= 1 (:exit r)))))
  (testing "and the run HEADER names the direction. It printed NARROWED for
            any policy differing from the default, so a policy blocking on
            MORE announced itself as a relaxation — the one line that
            discloses the deviation was able to describe it backwards.
            REVERT-TO-BREAK: return the literal \"NARROWED\" from
            `describe-policy`'s deviation branch."
    (let [line (outcome/describe-policy (widened))]
      (is (str/includes? line "WIDENED"))
      (is (not (str/includes? line "NARROWED"))))
    (testing "CONTROL: a real narrowing still says NARROWED, so the assertion
              above is not true of every deviating policy"
      (is (str/includes? (outcome/describe-policy (narrowed)) "NARROWED"))))
  (testing "and the header carries the WHOLE proof now — a global concession
            whose owner and expiry never reach the log is one nobody can route
            or retire"
    (let [line (outcome/describe-policy (narrowed))]
      (is (str/includes? line "devcards-maintainer"))
      (is (str/includes? line (days-out 30))))))

(deftest an-UNSUPPLIED-armed-set-leaves-the-RATCHET-UNARMED-and-says-so
  (testing "the ratchet cannot fire without knowing what is armed, and the
            honest report of that is an admission rather than a pass. This is
            the same three-way the NOT-EXERCISED line already draws: 'I could
            not look' is not 'nothing to report'.
            REVERT-TO-BREAK: make the `(nil? emittable)` branch of
            `deviation-status`'s cond yield `:inert` — a determinate verdict
            computed from an absent input. DELETING the branch instead is not
            the mutation to run: `not-any?` would then call nil as a
            predicate and the namespace reds with an NPE, an ERROR that
            executes nothing and names no clause."
    (let [v (outcome/verdict [] (narrowed))
          line (first (filter #(str/starts-with? % "DEVIATION") (:lines v)))]
      (is (= :undetermined (:status (:deviation v))))
      (is (str/includes? (str line) "UNDETERMINED"))
      (is (str/includes? (str line) "Pass :producers"))
      (testing "and it does NOT fail the run. A verdict the run could not
                compute must not be spent as either colour, and the two-arity
                is a legitimate entry point — so this path is UNARMED and says
                so, never silently green"
        (is (zero? (:exit v))))))
  (testing "CONTROL: supplying the armed set over the identical inputs
            replaces the admission with a determinate verdict, so the line
            above is the missing input and not a constant"
    (let [r (dev-run [] (narrowed) three-way-producers)]
      (is (= :stale (:status r)))
      (is (not (str/includes? (str (:line r)) "UNDETERMINED"))))))

(deftest a-four-key-narrowing-still-makes-a-REGISTERED-cantTell-advisory
  (testing "the behaviour `devcards.findings-test`'s end-to-end canary asserts
            — a narrowed policy demotes a :cantTell to advisory — is UNCHANGED
            by the four keys; what changed is the proof the policy owes to say
            so. That fixture predates the accountability keys and carries only
            two, so it now trips `validate-policy!` and needs `:owner` +
            `:expires` added (its own `waiver` helper already completes
            exemptions the same way). This assertion is the same claim under a
            complete policy, so the required repair there is a fixture edit
            and not a behaviour change."
    (let [live [(act :act/outcome :cantTell :act/reason :noise-band)]]
      (is (zero? (outcome/exit-code live (narrowed) {:producers three-way-producers})))
      (is (empty? (outcome/blocking live (narrowed))))
      (testing "CONTROL: the two-key policy that fixture uses is now refused,
                and the refusal names the missing key rather than the
                behaviour"
        (is (str/includes?
             (str (msg #(outcome/blocking
                         live
                         {:fail-outcomes #{:failed} :fail-modes #{:automatic}
                          :rationale "arming a contrast lane on a fresh corpus"
                          :retires-when "the corpus is clean under the default"})))
             ":owner must be a non-blank string"))))))

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
