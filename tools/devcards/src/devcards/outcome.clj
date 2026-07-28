(ns devcards.outcome
  "The ACT/EARL verdict vocabulary, the run-level policy that turns it into a
   process exit code, and the TOTAL report that has to survive both.

   Its own namespace, and that is FORCED rather than stylistic:
   `devcards.findings` requires `devcards.invariants`, and the exemption
   matcher in `invariants` has to read the same vocabulary the producer
   registry validates. Putting it in either one is a require cycle.

   FIVE values, not three. Three DIFFERENT non-answers are spelled
   identically today — as an absent finding:

     the score sits in the noise band      -> :cantTell + a declared reason
     there is no glyph ink in this region  -> :inapplicable + a declared reason
     no mask was supplied / no lane ran    -> :untested + a declared reason

   `devcards.overlap`'s reachability already draws that line by hand, in
   prose, between :unreachable (a determination, so no finding) and
   :unmeasurable (an admission, so a finding). This namespace is that
   distinction promoted to a value the registry can check.

   The TEST MODE axis is orthogonal to the outcome and is what lets a
   non-reproducible lane ride the same finding vector without being mistaken
   for a deterministic one. It is a PRODUCER declaration (`:test-mode` on the
   registry entry, stamped onto that producer's findings), so it reaches
   exactly the lanes armed through `devcards.findings` — which today's VLM
   review is not. See `default-fail-modes` for what that does and does not
   cover.

   THE AXES ARE NAMESPACED ON A FINDING (`:act/outcome`, `:act/test-mode`,
   `:act/reason`) AND THAT IS A COMPATIBILITY REQUIREMENT, NOT A STYLE.
   Before these axes existed a finding was an OPEN map: `check-findings!`
   required :card + :invariant and permitted anything else, and the VLM
   review's own briefing documents it as open. protogen is trunk-only
   upstream for a consumer fleet, so taking the three plainest words in the
   vocabulary — :outcome, :test-mode, :reason — would have been a migration
   every consumer had to answer at its next pin bump. Measured on the
   unnamespaced shape: a consumer producer emitting :reason \"…\" returned
   normally at HEAD and threw afterwards; and a legacy finding carrying
   :reason stopped matching the legacy exemption written for it, because the
   matcher had started reading that key. Under the namespaced axes both are
   untouched payload. Absent still means what it always meant, at every
   layer, without a single consumer edit.

   The PLAIN names stay reserved for producers that OPT IN (`:outcomes` /
   `:test-mode` / `:reasons` on the producer map): for those, `:outcome` on a
   finding is a near-miss of `:act/outcome` and throws. No producer that
   exists today opts in, so that strictness reaches nobody by construction.

   The report this namespace builds NAMES NO SOURCE STANDARD. The vocabulary
   is borrowed verbatim and the provenance belongs in these docstrings; a run
   that printed a standard's name would be claiming a conformance no gate
   here measures. `report-lines` is pinned against that by its own canary.

   Requires nothing, so it loads under the :test alias, which `devcards.core`
   does not (core drags `devcards.fixtures` and the generated bindings). That
   is the same reason `devcards.lanes` exists: an exit rule that cannot be
   named in a test cannot be pinned by one. So the gate's exit expression is
   NOT here and is not in `core.clj` either — it is
   `devcards.lanes/run-verdict`, which core calls and a canary pins. This
   namespace owns the RULE; that fn owns the gate's application of it.

   ONE computation, two accessors. `verdict` is the whole answer — lines and
   exit in one total step — and `exit-code` / `report-lines` are projections
   of it. That is structural, not tidiness: an exit fn computed independently
   of the report fn is an exit fn that can disagree with what the run just
   printed, and this file shipped exactly that disagreement for one review
   cycle (`exit-code` delegated to the THROWING `blocking` while `verdict`
   failed closed, so a malformed policy printed 'failing CLOSED' and exited
   0). `blocking` remains, and is the only strict entry point; nothing that
   decides a process verdict may call it."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def outcomes
  "W3C ACT Rules Format §9 / EARL outcome, VERBATIM — camelCase included. No
   translation table: a spelling this repo has to map onto the standard is a
   spelling that can disagree with it."
  #{:passed :failed :cantTell :inapplicable :untested})

(def test-modes
  "earl:TestMode, ORTHOGONAL to the outcome. A manual cantTell and an
   automatic cantTell are the same verdict from incomparable evidence, and
   only this axis can say so. Declared PER PRODUCER, never per finding — a
   finding that could name its own mode would let a non-reproducible lane
   claim :automatic one finding at a time."
  #{:automatic :semiAuto :manual})

(def axis-keys
  "The three axes AS THEY APPEAR ON A FINDING (and on an exemption, which
   narrows on the same axes). Namespaced — see the ns docstring; the short
   version is that the unnamespaced names were already legal payload in an
   open map, so taking them was a migration.

   This set is the ONE home of that list: the entitlement check below, the
   producer registry's near-miss refusal and the exemption key set all read
   it rather than re-spelling it."
  #{:act/outcome :act/test-mode :act/reason})

(def reserved-plain-keys
  "{plain-key -> the axis it is a near-miss of}. Refused on a finding from a
   producer that DECLARED ACT axes, and left alone on every other, which is
   the whole backward-compatibility seam in one map. A declaring producer
   that writes :outcome would otherwise be read as :failed — not a silent
   loosening (:failed blocks), but a silent MISREPORT: the by-outcome tally
   would be wrong and no exemption written against the finding could match
   it."
  {:outcome :act/outcome :test-mode :act/test-mode :reason :act/reason})

(def default-outcome
  "The backward-compatibility pin. A finding with no :act/outcome is :failed,
   because that is exactly what the pre-policy exit meant: any entry in the
   vector blocked. Absent must keep meaning what it already means."
  :failed)

(def default-test-mode :automatic)

(def legacy-outcomes
  "What a producer that declares no :outcomes may emit — the TIGHTEST set
   consistent with every producer that exists today, not the most permissive
   one. A producer that LATER starts emitting :cantTell throws instead of
   quietly ceasing to block, which is the whole point of the declaration."
  #{:failed})

(def unreportable-outcomes
  "Outcomes a FINDING may never carry, at any layer.

   :passed is a CATEGORY ERROR here and refusing it closes the cheapest
   laundering path this design had. A finding is, by construction, something
   a rule REPORTED; this vector carries no per-(rule, target) result model,
   so there is no sense in which a reported entry can be a pass. What
   :passed on a finding could only ever mean is 'suppressed' — and it would
   have been the cheapest possible suppression: two extra keywords, no
   reason, no doc, no :rationale, no :retires-when, and no policy edit,
   which would have made the least-reviewed path to a green gate also the
   easiest one.

   Refused at BOTH layers on purpose — a producer may not declare it, and a
   finding may not carry it — because those are different populations: the
   declaration check cannot see the corpus gates and the interaction lane,
   which never meet the registry at all."
  #{:passed})

(def reasoned-outcomes
  "Every outcome that owes a DECLARED reason. Derived, so it cannot drift
   from the vocabulary: everything except the default (:failed, what every
   legacy finding already is, and the one outcome whose meaning needs no
   further word) and the unreportable ones.

   Widened from :cantTell alone deliberately. :inapplicable and :untested are
   non-blocking-by-shape too — :inapplicable under every policy, :untested
   under any narrowed one — so leaving them reason-free left two outcomes a
   real defect could be relabelled into for free. They are also the two
   halves of the same obligation from the other direction: :inapplicable says
   'the clause ran and this target was out of its scope', :untested says 'the
   clause did not run here'. Both are only evidence if they say WHICH, and a
   closed reason vocabulary with non-blank docs is how they say it."
  (into #{} (remove #(or (= default-outcome %) (contains? unreportable-outcomes %)))
        outcomes))

(def default-fail-outcomes
  "IBM Equal Access's disposition, not Android ATF's: a needs-review result
   BLOCKS. Arming a three-way lane at #{:failed} alone is the silent skip
   dressed as policy — the cantTell would sit in out/findings.edn and the run
   would still go green.

   :untested blocks too, which has no IBM analogue because IBM runs every
   rule. Here it is 'an unjudged element is a FINDING, never a skip' in
   machine form, and it is the weaker of the two consistent choices: the
   registry's existing answer to an unsupplied input is a THROW."
  #{:failed :cantTell :untested})

(def default-fail-modes
  "Only a producer that DECLARES itself deterministic may set the exit code.
   The mode is a producer-level declaration — `:test-mode` on the registry
   entry, which `devcards.findings` stamps onto that producer's findings — so
   this set narrows exactly one population: a lane armed through the registry
   declaring `:test-mode :manual`. Nothing in this repo declares one, so the
   set is armed ahead of the lane it is for.

   IT DOES NOT COVER THE VLM REVIEW, which is the population most likely to be
   assumed under it. Those findings are written BY HAND in the
   `{:card :invariant :node :detail}` shape, pass through no producer, and so
   carry the default :automatic — measured: `blocking` keeps one and
   `lanes/run-verdict` exits 1. What holds that review out of the process
   verdict is that no path feeds it in (`.claude/rules/devcards.md`, 'Do not
   wire it into the verdict'), which is a convention this set does not
   enforce."
  #{:automatic})

(def default-policy
  {:fail-outcomes default-fail-outcomes :fail-modes default-fail-modes})

(def ^:private policy-keys
  #{:fail-outcomes :fail-modes :rationale :retires-when})

(defn- policy-error
  [policy problem]
  (throw (ex-info (str "malformed verdict policy: " problem) {:policy policy})))

(defn validate-policy!
  "Shape check for a run's verdict policy. It was written to mirror
   `invariants/validate-exemptions!`: a policy that DEVIATES from the shipped
   default owes the proof-carrying :rationale + :retires-when, because it is
   the same act — a declared decision to stop failing on something.

   THE MIRROR IS NOW BROKEN AND THE GAP RUNS THE WRONG WAY, which is why this
   says so rather than still claiming the parallel. An exemption now owes four
   proof keys — `invariants/exemption-proof-keys` — including an :owner and an
   :expires the validator can enforce, so a per-card waiver EXPIRES and a
   human is named on it. A policy deviation owes only the two strings, so it
   is permanent and unattributed.

   That is the larger hole of the two, not the smaller one. An exemption is
   scoped to one (card, invariant, node, outcome, mode, reason) tuple and is
   ratcheted by the :stale-exemption finding when it stops matching. A
   `:fail-outcomes` that drops :cantTell is GLOBAL, matches nothing it can go
   stale against, and silences a whole verdict class on every card at once —
   the exact 'a disabled rule is an unrecorded permanent exception' shape the
   waiver work exists to remove. Closing it is the same four keys plus an
   expiry clause here; it is not done, and it needs `outcome-test` and the
   policy fixtures in `findings-test` moved with it.

   Each of the two sets has a FLOOR it may not drop. A definite,
   deterministic defect that does not block is not a policy, it is a disabled
   gate — and an empty set is the whole-gate form of the empty producer set
   `validate-producers!` already refuses.

   The NON-EMPTY SET clause and the FLOOR clause below look redundant and are
   not, which was measured: with the set clause deleted the suite stayed
   green, because #{} trips the floor — but nil then reported 'may not drop
   :failed' (true of every wrong value, so it names nothing) and a LIST threw
   a raw IllegalArgumentException out of `contains?`. The set clause is the
   only one that produces a correct message for a non-set, and each shape is
   pinned by message rather than by `thrown?`."
  [policy]
  (when-not (map? policy) (policy-error policy "not a map"))
  (when-let [extra (seq (remove policy-keys (keys policy)))]
    (policy-error policy (str "unknown keys " (vec extra)
                              " — declared: " (vec (sort policy-keys)))))
  (doseq [[k allowed floor refused]
          [[:fail-outcomes outcomes :failed unreportable-outcomes]
           ;; no mode is unreportable — every test-mode can reach a finding
           [:fail-modes test-modes :automatic #{}]]]
    (let [v (get policy k)]
      (when-not (and (set? v) (seq v))
        (policy-error policy (str k " must be a NON-EMPTY set, got "
                                  (pr-str v))))
      (when-let [bad (seq (remove allowed v))]
        (policy-error policy (str k " names unknown values " (vec bad)
                                  " — declared: " (vec (sort allowed)))))
      ;; The symmetric half of `invariants/validate-exemptions!`'s
      ;; "stale from birth" refusal. :passed is a legal OUTCOME but
      ;; `unreportable-outcomes` guarantees it never reaches a finding, so
      ;; naming it here arms a clause that can never fire — config that reads
      ;; as a tightening and is inert. Refused rather than tolerated for the
      ;; same reason an unknown threshold key throws: a knob that looks armed
      ;; and is not is worse than one that is rejected.
      (when-let [dead (seq (filter refused v))]
        (policy-error policy (str k " names " (vec dead) " — never reachable "
                                  "on a finding (see unreportable-outcomes), "
                                  "so the clause could never fire")))
      (when-not (contains? v floor)
        (policy-error policy (str k " may not drop " floor)))))
  (when (not= (select-keys policy [:fail-outcomes :fail-modes]) default-policy)
    (doseq [k [:rationale :retires-when]]
      (when-not (and (string? (get policy k)) (not (str/blank? (get policy k))))
        (policy-error policy (str "a policy that differs from the shipped "
                                  "default owes " k " — the proof is "
                                  "mandatory, exactly as it is for an "
                                  "exemption")))))
  policy)

(defn finding-outcome
  "`f`'s ACT outcome, defaulted. Used on findings AND on exemptions, which is
   why it is a function rather than an inline `get`: the exemption matcher
   compares the two sides, and a default spelled twice is a default that can
   be changed once."
  [f]
  (get f :act/outcome default-outcome))

(defn finding-mode
  "`f`'s test mode, defaulted. Same both-sides argument as `finding-outcome`."
  [f]
  (get f :act/test-mode default-test-mode))

(defn axis-problem
  "nil when `f`'s ACT axes are well-formed, else a one-line problem string.

   TOTAL and pure — it never throws, which is what lets the report be
   computed and PRINTED before anything decides to fail on it.

   The ENTITLEMENT clause is the one worth reading. The vocabulary check
   validates the VALUE an axis carries; it says nothing about whether the
   finding was allowed to carry the key at all. The mode is 'declared PER
   PRODUCER, never per finding', and the registry enforces that — but only
   for registry-routed findings, and the populations that most need policing
   are exactly the ones that bypass it: the corpus gates and the interaction
   lane build {:gate :card :detail} maps straight into the terminal vector. A
   hand-built finding could therefore have stamped itself :manual and walked
   out of the verdict. `:producer` is the registry's own stamp and is the
   available discriminator: no axis without it. That is a structural check,
   not a cryptographic one — a hand-built map could also fake `:producer` —
   and it is stated that way rather than overclaimed.

   A NON-MAP element is itself a problem, reported rather than thrown. The
   registry refuses one (`findings/check-findings!` requires `map?`), but the
   populations named above never meet the registry, and `contains?` throws on a
   string or a keyword — which would delete the whole report after
   `out/findings.edn` was already written. Reporting it keeps the totality this
   docstring claims, and keeps `core`'s 'counts always print' true."
  [f]
  (let [o (when (map? f) (finding-outcome f))
        m (when (map? f) (finding-mode f))
        carried (when (map? f) (filter #(contains? f %) axis-keys))]
    (cond
      (not (map? f))
      (str "finding is " (pr-str (type f)) ", not a map — the ACT axes "
           "cannot be read from it")

      (not (contains? outcomes o))
      (str "finding carries " :act/outcome " " (pr-str o) " — not one of "
           (vec (sort outcomes)))

      (contains? unreportable-outcomes o)
      (str "finding carries " :act/outcome " " (pr-str o)
           " — a reported finding is by construction not a pass; this vector "
           "has no per-target result model, so " (pr-str o) " on a finding "
           "can only mean 'suppressed'")

      (not (contains? test-modes m))
      (str "finding carries " :act/test-mode " " (pr-str m) " — not one of "
           (vec (sort test-modes)))

      (and (seq carried) (not (contains? f :producer)))
      (str "finding carries " (vec (sort carried))
           " but no :producer — the ACT axes are the producer registry's "
           "declaration and the registry stamps them; a finding that never "
           "met the registry is not entitled to name its own outcome or mode")

      :else nil)))

(defn blocking
  "The findings a verdict FAILS on, under `policy`. STRICT: throws on the
   first malformed finding.

   This is the programmatic contract — a caller that wants an answer wants a
   correct one, and a typo'd axis has no correct answer. `verdict` is the
   TOTAL form for the reporting path, and it is what the CLI uses; nothing
   that prints a report may call this one, for the reason `verdict`'s
   docstring gives."
  [findings policy]
  (let [{:keys [fail-outcomes fail-modes]} (validate-policy! policy)]
    (filterv (fn [f]
               (when-let [problem (axis-problem f)]
                 (throw (ex-info problem {:finding f})))
               (and (contains? fail-outcomes (finding-outcome f))
                    (contains? fail-modes (finding-mode f))))
             findings)))

(defn emittable-outcomes
  "The outcomes an ARMED producer set can actually put in front of a policy
   whose blocking modes are `fail-modes`. Two filters, both load-bearing:

     :outcomes  — what the producer DECLARED it may emit, defaulted exactly as
                  the registry defaults it (`legacy-outcomes`, i.e. :failed
                  alone). A producer emitting anything else throws there, so
                  this union is a real ceiling, not an estimate.
     :test-mode — a producer outside `fail-modes` cannot contribute a BLOCKING
                  finding at all, so its outcomes are not in scope for a
                  question about blocking clauses. Without this filter a
                  :manual-only lane would have its declared :cantTell reported
                  as an unexercised blocking clause forever, which is the same
                  zero-bit line this function exists to delete.

   Producer maps are read positionally-free, by key: this ns owns the two key
   names' defaults already (`devcards.findings` reads them from here), so
   knowing the shape is not a layering break."
  [producers fail-modes]
  (into #{}
        (comp (filter #(contains? fail-modes (:test-mode % default-test-mode)))
              (mapcat #(:outcomes % legacy-outcomes)))
        producers))

(defn describe-policy
  "One line for the run header. A NARROWED policy prints its proof on every
   run: the least a decision to stop failing on something owes is a line in
   the log saying it was made."
  ^String [policy]
  (str "verdict policy: fail-outcomes " (vec (sort (:fail-outcomes policy)))
       " fail-modes " (vec (sort (:fail-modes policy)))
       (if (= (select-keys policy [:fail-outcomes :fail-modes]) default-policy)
         " (shipped default)"
         (str " (NARROWED — " (:rationale policy)
              "; retires when " (:retires-when policy) ")"))))

(defn- tally
  "`frequencies` over `ks`, WITH THE ZEROES. A frequencies map omits the keys
   nothing landed on, so '0 cantTell observed' and 'cantTell is not a value
   this run could produce' print identically — which is the whole silence
   this vocabulary exists to break. Sorted, so the line is byte-stable."
  [ks kf coll]
  (into (sorted-map) (merge (zipmap ks (repeat 0)) (frequencies (map kf coll)))))

(defn verdict
  "The run-level judgement AS DATA. TOTAL: no input makes it throw.

   That totality is the point, not a nicety. The reporting path used to
   compute the blocking set FIRST and print afterwards, so one out-of-
   vocabulary axis on a gate finding — the population that bypasses the
   registry, i.e. exactly the one this checking exists for — threw before the
   counts, before the dump and before the exit call, deleting the entire
   report and taking the process's verdict with it. 'Counts always print'
   cannot be true of a path whose first step can throw.

   So a malformed finding is not an exception here; it is a BLOCKING finding
   with its problem stated, which is the same rule the rest of this standard
   runs on: an unjudged element is a finding, never a skip. A malformed
   POLICY is likewise reported and fails CLOSED — the run fails ON ITS OWN
   ACCOUNT, not merely by re-labelling every finding as blocking. That
   distinction is the whole defect this clause shipped with for one review
   cycle: 'every finding blocks' over the EMPTY vector is zero blocking
   findings, so a one-character policy typo printed 'failing CLOSED' and then
   exited 0 on every clean corpus — a permanently green gate. `:exit` reads
   the policy problem directly and never infers it from a count.

   `opts` (optional) may carry `:producers` — the ARMED producer set. Supply
   it: `:not-exercised` is not computable without it (see below), and a report
   that cannot compute it says so rather than guessing.

   Returns
     {:blocking      [finding …]   what fails the run
      :malformed     [{:finding f :problem s} …]
      :by-outcome    {outcome -> n}   every vocabulary key, zeroes included
      :by-mode       {mode -> n}      likewise
      :emittable     #{outcome …}|nil what the armed set CAN emit and block on
      :not-exercised [outcome …]|nil  in-scope blocking outcomes seen 0 times,
                                      nil when the armed set was not supplied
      :lines         [string …]       the report, in print order
      :exit          0|1}"
  ([findings policy] (verdict findings policy nil))
  ([findings policy {:keys [producers]}]
   (let [checked (try (validate-policy! policy)
                      (catch Throwable t {::policy-problem (ex-message t)}))
         policy-problem (::policy-problem checked)
         {:keys [fail-outcomes fail-modes]} (if policy-problem default-policy
                                                checked)
         judged (mapv (fn [f] [f (axis-problem f)]) findings)
         malformed (into [] (for [[f p] judged :when p] {:finding f :problem p}))
         well-formed (into [] (for [[f p] judged :when (nil? p)] f))
         blocked (cond
                   policy-problem (vec findings)
                   :else (into (mapv :finding malformed)
                               (filter (fn [f]
                                         (and (contains? fail-outcomes
                                                         (finding-outcome f))
                                              (contains? fail-modes
                                                         (finding-mode f)))))
                               well-formed))
         by-outcome (tally outcomes finding-outcome well-formed)
         by-mode (tally test-modes finding-mode well-formed)
         ;; An outcome NOTHING ARMED CAN EMIT is not "not exercised", it is
         ;; out of scope, and naming it is worse than saying nothing: the
         ;; shipped policy blocks on :cantTell and :untested while every
         ;; producer protogen arms declares :failed alone, so the unfiltered
         ;; line named those two on EVERY run, red or green, forever. A
         ;; warning that fires identically on every possible run carries zero
         ;; bits — and it made the ONE state the line exists to flag (a
         ;; producer that DECLARED :cantTell and emitted none) byte-identical
         ;; to the permanent baseline. Intersecting with what the armed set
         ;; can emit is what makes the line a measurement again.
         ;; …and an UNREADABLE POLICY makes the line unanswerable, not
         ;; answerable from the default. The header says the shipped default is
         ;; not assumed; computing the scope from it anyway would report a
         ;; measurement against a policy the run just disclaimed. nil here
         ;; routes to the same UNDETERMINED line an absent armed set gets,
         ;; which is the honest answer for both.
         emittable (when-not policy-problem
                     (some-> producers (emittable-outcomes fail-modes)))
         not-exercised (when emittable
                         (vec (for [o (sort fail-outcomes)
                                    :when (and (contains? emittable o)
                                               (zero? (get by-outcome o 0)))]
                                o)))]
     {:blocking blocked
      :malformed malformed
      :by-outcome by-outcome
      :by-mode by-mode
      :emittable emittable
      :not-exercised not-exercised
      :lines
      (cond-> [(if policy-problem
                 (str "verdict policy is MALFORMED (" policy-problem
                      ") — failing CLOSED: the run fails on the policy itself, "
                      "independently of the " (count findings)
                      " finding(s); the shipped default is NOT assumed")
                 (describe-policy policy))
               (str "findings: " (count findings)
                    "  blocking: " (count blocked)
                    "  malformed: " (count malformed))
               (str "by outcome: " (pr-str by-outcome))
               (str "by mode: " (pr-str by-mode))]
        (empty? findings)
        (conj (str "NOT EXERCISED: 0 findings judged — every armed producer "
                   "returned empty. That is 'nothing to report'; it is 'all "
                   "clear' only if the armed producer set is the one you "
                   "meant."))

        ;; Two different reasons the scope is unknowable, and they must not be
        ;; reported as one. Naming the wrong cause is the same conflation this
        ;; whole line exists to refuse, one level down: a reader told to "pass
        ;; :producers" when they already did will go looking in the wrong place.
        (nil? emittable)
        (conj (str "NOT EXERCISED: UNDETERMINED — "
                   (if policy-problem
                     (str "the verdict policy is unreadable, so this run cannot "
                          "say which outcomes block, let alone which went "
                          "unexercised. Fix the policy above")
                     (str "the armed producer set was not supplied, so this run "
                          "cannot say which blocking outcomes were even in "
                          "scope. Pass :producers"))
                   ". 'I could not look' is not 'nothing to report'."))

        ;; The THIRD state, and it was silent. `emittable` is a set, so an
        ;; armed set that can emit no blocking outcome — empty, or entirely
        ;; outside fail-modes, e.g. a VLM-only :manual lane — yields #{},
        ;; which is truthy. Without this branch the run prints no scope line
        ;; at all, and "everything in scope fired" reads exactly like "nothing
        ;; was ever in scope". `validate-producers!` refuses an empty producer
        ;; vector at the registry for this very reason; the verdict must not
        ;; be laxer than the registry about the same hazard.
        (and emittable (empty? emittable))
        (conj (str "NOT EXERCISED: NOTHING IN SCOPE — no armed producer "
                   "declares a blocking outcome under this policy, so this "
                   "run could not have failed on a producer finding however "
                   "bad the corpus was. A green here is the shape of the "
                   "armed set, not a judgement about the cards."))

        (seq not-exercised)
        (conj (str "NOT EXERCISED: "
                   (str/join ", " (map #(str % " 0") not-exercised))
                   " — blocking outcome(s) an ARMED producer declares it can "
                   "emit and this run never produced. Zero observations is a "
                   "count, not a pass."))

        (seq malformed)
        (into (for [{:keys [finding problem]} malformed]
                (str "MALFORMED (blocking): " problem " — " (pr-str finding)))))
      :exit (if (or policy-problem (seq blocked)) 1 0)})))

(defn exit-code
  "The process verdict, and TRULY total: every input maps to 0 or 1 and no
   branch declines to answer, because it is a projection of `verdict` rather
   than a second opinion. It delegated to `blocking` for one review cycle,
   which made this docstring FALSE — a typo'd axis or a malformed policy threw
   out of a fn documented as total, and the number it returned could differ
   from the one the report had just printed. One computation, two accessors.

   Deliberately BINARY. A distinct 'nothing was decided' code was considered
   and rejected: computing it needs a per-(producer x card) coverage tally
   whose only available predicate is true on any corpus where the DOM lane
   runs, so the code could never fire — and a gate that cannot fire is the
   empty-producer-set hazard wearing a policy hat. What answers that question
   instead is reporting that always happens: the by-outcome counts, the
   NOT-EXERCISED lines, the printed policy, and the full vector persisted
   before the exit."
  ([findings policy] (:exit (verdict findings policy)))
  ([findings policy opts] (:exit (verdict findings policy opts))))

(defn report-lines
  "`verdict`'s report, ready to print. Separate accessor so a caller cannot
   accidentally couple to the rest of the map, and so the no-standard-named
   canary has one thing to read."
  ([findings policy] (:lines (verdict findings policy)))
  ([findings policy opts] (:lines (verdict findings policy opts))))
