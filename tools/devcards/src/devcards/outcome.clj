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

   IT IS ALSO THE HOME OF THE PROOF A CONCESSION OWES — `proof-keys`,
   `today`, `horizon-days`, `check-proof!` — and that placement is forced the
   same way this namespace's existence is. Two acts stop a finding from
   failing a run: a SCOPED waiver (`devcards.invariants`) and this file's
   GLOBAL policy deviation. `devcards.invariants` requires THIS ns, so a
   shared definition can only live here; put it there and the policy side
   would need a require that closes a cycle, and the only remaining option is
   to spell the four keys and the two expiry clauses twice — in the one design
   whose entire subject is two accountability shapes drifting apart.

   Requires no devcards namespace, so it loads under the :test alias, which
   `devcards.core` does not (core drags `devcards.fixtures` and the generated
   bindings — `java.time` is an import and costs nothing here). That
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
  (:require [clojure.string :as str])
  (:import (java.time LocalDate ZoneOffset)
           (java.time.format DateTimeParseException)
           (java.time.temporal ChronoUnit)))

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

;; ── the PROOF a concession owes, in ONE home ─────────────────────────────
;; Everything from here to `check-proof!` is shared by the two acts that stop
;; a finding from failing a run. `devcards.invariants` reads it for the scoped
;; WAIVER; `validate-policy!` below reads it for the GLOBAL deviation. See the
;; ns docstring for why the shared home can only be this file.

(def horizon-days
  "The OUTER BOUND on a concession's life, in days. An entry whose `:expires`
   is further out than this is refused at write time.

   The horizon is a separate clause from expiry and catches the opposite
   mistake: expiry catches a concession that OUTLIVED its decision, the
   horizon catches one written never to lapse in the first place. Without it,
   `:expires \"2099-01-01\"` satisfies every other clause here and is a
   permanent concession carrying a date — precisely the shape `:retires-when`
   prose already was.

   Measured against the clock rather than against a write date, deliberately:
   nothing records when an entry was authored, and a stored write date would
   be a second fact free to disagree with git. `expires <= today + 90` is
   stable under the passage of time — the gap only shrinks — so an entry that
   passed on the day it was written can never start failing THIS clause
   later. Only the expiry clause fires with time, which is what expiry is.

   ONE HORIZON FOR BOTH ACTS, and the alternative was considered rather than
   skipped. A global deviation could be argued onto a SHORTER leash than a
   card-scoped waiver, since it silences a whole verdict class — but any
   shorter number would be an unsourced constant no measurement here supports,
   and this repo refuses those. A LONGER one is the inversion this whole
   design removes: more rope for the broader act. So: the same number, and the
   decision is recorded here rather than left to look like an oversight."
  90)

(defn today
  "The gate's clock: the current date in UTC.

   UTC RATHER THAN THE DEFAULT ZONE, and this is not fussiness. protogen is
   the pinned upstream for a 10+ repo fleet whose CI runners and operators sit
   in unknown zones; with a local-zone clock the same concession is expired in
   one checkout and live in another for up to a day, and the two disagree
   about whether the gate is red. One day boundary for the whole fleet.

   Callers pass a date explicitly wherever the answer must be reproducible —
   `validate-policy!` and `invariants/validate-exemptions!` both take one — so
   this is the DEFAULT and never the only source."
  ^LocalDate []
  (LocalDate/now ZoneOffset/UTC))

(def proof-keys
  "The PROOF a concession owes, independent of what it applies to. All four
   are mandatory non-blank strings.

   :rationale and :retires-when are the argument: why this finding is not a
   defect, and what event makes the concession unnecessary. :owner and
   :expires are the ACCOUNTABILITY — an entry carrying only the first two is a
   decision with no author and no end.

   :retires-when SURVIVES the arrival of :expires, and merging them would
   lose the half that matters. The retirement CONDITION is a real-world event
   ('the mask emitter lands') that no machine here can evaluate; the DATE is
   the outer bound at which the decision must be re-taken whether or not that
   event happened. Keeping only the date tells you WHEN to look and not WHAT
   for; keeping only the prose is what let a concession be permanent while
   claiming otherwise. Both, or neither is worth having.

   WHAT :owner CANNOT SEE, said out loud so no pass message over-claims: the
   check is `non-blank string`. `:owner \"TODO\"` passes. What it buys is not
   verification, it is that the expiry failure below has a name in it to route
   to; an unfalsifiable allowlist of real humans would be a second register
   free to rot against the team.

   THE SAME FOUR FOR BOTH ACTS. A `:fail-outcomes` that drops :cantTell is
   GLOBAL — it silences a whole verdict class on every card at once — while a
   waiver is scoped to one (card, invariant, node, outcome, mode, reason)
   tuple. There is no reading on which the broader act owes the smaller proof,
   and for one release cycle it owed exactly that: two prose strings, no
   author, no end. `devcards.invariants/exemption-proof-keys` is this set."
  #{:rationale :retires-when :owner :expires})

(def ^:private prose-proof-keys
  "The three proof keys whose whole check is `non-blank string`. :expires is
   the fourth and is checked separately, because non-blank is only its first
   clause; deriving this vector from `proof-keys` minus :expires would read as
   though the two halves were interchangeable. Sorted, so a multi-defect entry
   always names the same key first."
  [:owner :rationale :retires-when])

(defn plural-days
  "`n` as \"1 day\" / \"3 days\". One home, because every message here counts
   days and a red morning is the worst time to be reading `1 days`.

   PUBLIC because `invariants/waiver-summary` counts days too and re-spelling
   it there is how the two would come to disagree about the singular."
  ^String [n]
  (str n " day" (when (not= 1 n) "s")))

(defn- proof-expiry
  "`m`'s :expires as a `LocalDate`, or `(error! …)` naming exactly which shape
   it failed. Two clauses, not one, and they are kept apart because they
   diagnose different mistakes: a missing/blank/non-string :expires is an
   entry that never tried to have a date, while an unparseable one is a date
   typed wrong (`\"2026-13-01\"`, `\"soon\"`, `\"1/1/2026\"`).

   A STRING and never a `java.time.LocalDate` literal, for the reason `:node`
   already carries on the waiver side: a concession is DATA — committed,
   reviewed and round-tripped through EDN — and `LocalDate` has no EDN reader,
   so an entry carrying one could not survive the file it lives in. `#inst`
   would round-trip but drags a time-of-day and a zone that mean nothing to a
   day-resolution decision."
  ^LocalDate [m error!]
  (let [v (:expires m)]
    (when-not (and (string? v) (not (str/blank? v)))
      (error! (str ":expires must be an ISO-8601 date STRING (YYYY-MM-DD) — a "
                   "concession with no date cannot expire, and it is EDN data "
                   "like every other key here rather than a Java object")))
    (try
      (LocalDate/parse ^String v)
      (catch DateTimeParseException _
        (error! (str ":expires " (pr-str v) " is not an ISO-8601 "
                     "date (YYYY-MM-DD)"))))))

(defn check-proof!
  "Assert `m` carries all four `proof-keys`, judged against `now`. `error!` is
   the caller's one-argument refusal (it throws; nothing here returns from
   it), and `subject` is the noun its messages read with — \"waiver\" for a
   scoped exemption, \"policy deviation\" for a global one. Returns `m`.

   THE SUBJECT IS A NOUN AND NOT A SECOND CODE PATH. Both callers run these
   exact five clauses in this exact order; only the word in the sentence
   differs, so a red is still readable back to the act that produced it while
   the clauses themselves cannot drift. That is the whole reason this fn
   exists rather than being spelled once per caller — the defect it was
   written to close was two accountability shapes diverging while both looked
   like proof.

   Nothing here is a function of what `m` otherwise IS. That is what makes one
   home possible: the proof a concession owes has never depended on what it
   concedes."
  [m ^LocalDate now error! subject]
  (doseq [k prose-proof-keys]
    (when-not (and (string? (get m k)) (not (str/blank? (get m k))))
      (error! (str k " must be a non-blank string — the proof a " subject
                   " owes is mandatory"))))
  ;; ── the two expiry clauses ──────────────────────────────────────────────
  ;; They can never both fire (a date cannot be both before `now` and more
  ;; than the horizon after it), so neither can mask the other and their order
  ;; carries no meaning. Each message is written to contain a token the other
  ;; does not — "EXPIRED" against "at most" — because a canary that can only
  ;; assert `thrown?` cannot tell a clause from its neighbour, and these two
  ;; are the closest neighbours here: they read the SAME key.
  (let [^LocalDate expires (proof-expiry m error!)]
    (when (.isBefore expires now)
      (error! (str subject " EXPIRED on " expires " ("
                   (plural-days (.between ChronoUnit/DAYS expires now))
                   " ago) — re-take the decision or fix the finding. Owner: "
                   (pr-str (:owner m)))))
    (when (.isAfter expires (.plusDays now horizon-days))
      (error! (str ":expires " expires " is "
                   (plural-days (.between ChronoUnit/DAYS now expires))
                   " out — a " subject " may run at most " horizon-days
                   " days (outcome/horizon-days). A date beyond the horizon is "
                   "a permanent concession with a date on it."))))
  m)

(def ^:private policy-keys
  "Every key a policy may carry: the two blocking sets, plus the proof any
   deviation from the shipped default owes. DERIVED from `proof-keys` rather
   than re-spelled — a re-spelled list is how a mandatory key ends up refused
   as unknown by the very validator that demands it."
  (into #{:fail-outcomes :fail-modes} proof-keys))

(defn- policy-error
  [policy problem]
  (throw (ex-info (str "malformed verdict policy: " problem) {:policy policy})))

(defn deviation
  "What `policy` CHANGES about the shipped default's blocking sets, as data —
   or nil when it changes nothing. TAKES AN ALREADY-SHAPE-CHECKED POLICY (both
   fail sets non-empty sets of known values); `validate-policy!` calls it only
   after those clauses, and `describe-policy` / `verdict` only on a policy that
   passed them.

     {:demoted-outcomes #{…}  what the default blocked and this does not
      :demoted-modes    #{…}  likewise on the orthogonal axis
      :added-outcomes   #{…}  what this blocks and the default does not
      :added-modes      #{…}
      :kind :narrowing|:widening}

   THE KIND IS NOT COSMETIC and the two halves are not symmetric, which is the
   whole reason this returns structure rather than a boolean. A NARROWING is a
   declared decision to stop failing on something: it is the act the four
   `proof-keys` and the staleness ratchet exist for. A WIDENING gates MORE
   strictly than upstream, so it cannot go stale by doing nothing — a guard
   that catches nothing is a guard, while a concession that concedes nothing
   is debt. `deviation-status` reads exactly that asymmetry.

   Mixed counts as a NARROWING. A policy that drops :untested and adds
   :inapplicable has stopped failing on something, and letting the addition
   launder that would be the cheapest possible route around the proof.

   :demoted-modes is DERIVED and is empty for every policy that can exist
   today: `default-fail-modes` is `#{:automatic}`, which is also the mode
   floor, so the mode axis can only be widened. It is computed rather than
   asserted because that coincidence is a property of two other defs, and a
   hardcoded #{} here would silently stop being true the day either moves."
  [policy]
  (let [{do* :fail-outcomes dm :fail-modes} default-policy
        {po :fail-outcomes pm :fail-modes} policy
        demoted-o (into #{} (remove po) do*)
        demoted-m (into #{} (remove pm) dm)
        added-o (into #{} (remove do*) po)
        added-m (into #{} (remove dm) pm)]
    (when (or (seq demoted-o) (seq demoted-m) (seq added-o) (seq added-m))
      {:demoted-outcomes demoted-o
       :demoted-modes demoted-m
       :added-outcomes added-o
       :added-modes added-m
       :kind (if (or (seq demoted-o) (seq demoted-m)) :narrowing :widening)})))

(defn validate-policy!
  "Shape check for a run's verdict policy, judged against `now` (the 1-arity
   reads `today`, UTC). It MIRRORS `invariants/validate-exemptions!`: a policy
   that DEVIATES from the shipped default owes the four proof-carrying
   `proof-keys`, because it is the same act — a declared decision about what
   stops failing a run — and it reaches them through the same
   `check-proof!`, so the two cannot drift.

   THE MIRROR WAS BROKEN AND THE GAP RAN THE WRONG WAY. For one release cycle
   an exemption owed four keys — an :owner and an :expires a validator could
   enforce — while a policy deviation owed two prose strings, so the SCOPED
   act expired and named a human and the GLOBAL one did neither. That is the
   larger hole of the two, not the smaller one: an exemption is scoped to one
   (card, invariant, node, outcome, mode, reason) tuple, while a
   `:fail-outcomes` that drops :cantTell silences a whole verdict class on
   every card at once. No reading makes the broader act owe the smaller proof,
   so the deviation was brought up to the waiver rather than the reverse.

   ALL FOUR ARE OWED BY A WIDENING TOO, which is the one place this deviates
   from the obvious design. A widening gates more strictly than the shipped
   default, so an expiry on it looks backwards — a tightening that lapses. It
   does not lapse SILENTLY: an expired policy is refused here, `verdict`
   reports it and fails CLOSED, so the failure mode is a red run naming the
   date, never a quiet relaxation. What the expiry buys is that a local fork of
   the fleet's gate posture gets re-taken on a schedule instead of outliving
   the reason for it. One key list, no second code path, and no clause that is
   owed by one kind of deviation and not the other.

   THE PROOF IS THE ONLY THING THIS FN CAN CHECK. Whether the deviation is
   INERT or STALE is a question about the ARMED PRODUCER SET and about what
   the run actually produced, neither of which a shape check can see —
   `deviation-status` owns those, and `verdict` calls it.

   ONE FIXTURE OUTSIDE THIS FILE PREDATES THE TWO NEW KEYS and is red until it
   carries them: the inline policy in `devcards.findings-test`'s
   `a-REGISTERED-cantTell-reaches-the-EXIT-CODE`, which asserts a narrowed
   policy makes a registered :cantTell advisory. The BEHAVIOUR it asserts is
   unchanged — `outcome-test`'s
   `a-four-key-narrowing-still-makes-a-REGISTERED-cantTell-advisory` pins the
   same claim under a complete policy — so the repair is `:owner` plus
   `:expires` on that map, exactly as that namespace's own `waiver` helper
   already completes its exemptions from the live clock.

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
  ([policy] (validate-policy! policy (today)))
  ([policy ^LocalDate now]
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
       ;;
       ;; It is the VOCABULARY-LEVEL case of the same question
       ;; `deviation-status` asks against the armed set: this one is decidable
       ;; from the vocabulary alone, so it is refused at write time rather
       ;; than reported at run time.
       (when-let [dead (seq (filter refused v))]
         (policy-error policy (str k " names " (vec dead) " — never reachable "
                                   "on a finding (see unreportable-outcomes), "
                                   "so the clause could never fire")))
       (when-not (contains? v floor)
         (policy-error policy (str k " may not drop " floor)))))
   (when (deviation policy)
     (check-proof! policy now #(policy-error policy %) "policy deviation"))
   policy))

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

(defn- blocks?
  "Would `f` fail a run whose blocking sets are `fail-outcomes`/`fail-modes`?
   The conjunct `blocking` and `verdict` both apply, named once so the
   demotion arithmetic below cannot answer a slightly different question than
   the verdict it is reasoning about."
  [f fail-outcomes fail-modes]
  (and (contains? fail-outcomes (finding-outcome f))
       (contains? fail-modes (finding-mode f))))

(defn demoted-findings
  "The findings `policy` lets through that the SHIPPED DEFAULT would have
   blocked — what the deviation actually bought, per run.

   This is the policy-side answer to 'what does this concession match?', and
   answering it is the whole reason a deviation can be ratcheted at all. A
   waiver matches findings by tuple, so `apply-exemptions` can see one that
   matches nothing; a deviation matches nothing by construction, which is
   exactly why it had nothing to go stale against. Demotion is the relation
   that was missing: it is computed against `default-policy` rather than
   against the deviation's own declared sets, so a change to the shipped
   default carries this with it and cannot leave it behind.

   Malformed findings are excluded: they block unconditionally under every
   policy (`verdict` puts them in :blocking whatever the sets say), so no
   policy can demote one and counting them here would let a typo'd axis keep a
   stale deviation looking live."
  [policy findings]
  (let [{do* :fail-outcomes dm :fail-modes} default-policy
        {po :fail-outcomes pm :fail-modes} policy]
    (filterv #(and (nil? (axis-problem %))
                   (blocks? % do* dm)
                   (not (blocks? % po pm)))
             findings)))

(defn deviation-status
  "THE RATCHET A POLICY DEVIATION HAD NO ANALOGUE FOR, as data — or nil when
   `policy` is the shipped default.

   `invariants/apply-exemptions` reports a waiver that matched NO finding as a
   :stale-exemption, so the waiver list can only shrink. A deviation had
   nothing of the kind, and 'nothing to go stale against' was the defect
   rather than a missing convenience: a `:fail-outcomes` narrowed once stayed
   narrowed forever, silently, whatever the corpus later did. `demoted-findings`
   supplies the missing relation and this fn is the ratchet over it.

   Returns the `deviation` map plus:

     :status :live         — the deviation demoted at least one finding, so it
                             is doing what it was written to do
             :stale        — NOTHING was demoted, though the armed set CAN
                             emit an outcome this deviation drops. The
                             concession bought nothing this run; remove it and
                             the run is green under the shipped default. This
                             is the :stale-exemption ratchet, one scope up
             :inert        — no armed producer declares ANY outcome this
                             deviation touches, so it could not have changed a
                             single verdict however bad the corpus was. This
                             is `validate-policy!`'s :passed 'never reachable'
                             refusal against the ARMED SET instead of against
                             the vocabulary — the same defect, and only
                             decidable here because a shape check cannot see
                             which producers are armed
             :undetermined — the armed set was not supplied. 'I could not
                             look' is not 'nothing to report'
     :demoted n            — how many findings it demoted. ALWAYS 0 for a
                             widening, by definition, which is why the widening
                             line reports :promoted instead: a number that is
                             zero whatever happened is not a disclosure
     :promoted n           — the mirror: findings this policy blocks that the
                             shipped default would have let through. Always 0
                             for a pure narrowing, by the same definition

   THE TWO KINDS ARE NOT SYMMETRIC UNDER STALENESS, and the asymmetry is the
   argument rather than an omission. A NARROWING that demoted nothing is a
   concession that conceded nothing — debt with no purchase. A WIDENING that
   blocked nothing extra is a guard over a clean corpus, which is the state it
   was written to reward; calling that stale would ratchet OUT the strictest
   policies. So staleness is narrowing-only. INERTNESS applies to both,
   because a clause nothing armed can reach is inert whichever direction it
   points, and a widening onto an unemittable outcome is precisely the
   'reads as a tightening and is not' shape the :passed clause already
   refuses.

   `emittable` is `emittable-outcomes`' answer for the armed set, or nil when
   none was supplied. Judged against the deviation's WHOLE touched set —
   demoted plus added — so :inert is claimed only when the deviation could not
   have moved any verdict at all. A mixed policy whose demoted half alone is
   unreachable is therefore not called inert; it is caught by :stale instead,
   which is the weaker and truer statement.

   TOTAL and pure: no input makes it throw, for the reason `verdict` is total."
  [policy findings emittable]
  (when-let [{:keys [kind demoted-outcomes added-outcomes] :as dev}
             (deviation policy)]
    (let [{do* :fail-outcomes dm :fail-modes} default-policy
          {po :fail-outcomes pm :fail-modes} policy
          touched (into demoted-outcomes added-outcomes)
          demoted (demoted-findings policy findings)
          ;; The mirror of `demoted-findings`, inline because only the
          ;; widening's report line reads it — the ratchet does not, since a
          ;; guard that caught nothing is not stale.
          promoted (filterv #(and (nil? (axis-problem %))
                                  (blocks? % po pm)
                                  (not (blocks? % do* dm)))
                            findings)]
      (assoc dev
             :demoted (count demoted)
             :promoted (count promoted)
             :status (cond
                       (nil? emittable) :undetermined
                       (not-any? emittable touched) :inert
                       (and (= :narrowing kind) (empty? demoted)) :stale
                       :else :live)))))

(def ^:private deviation-problem-statuses
  "The two `deviation-status` verdicts that FAIL a run. Both are the same
   finding a stale waiver is — a recorded concession that is not earning its
   record — and both must therefore red the gate rather than print a warning,
   or the ratchet is decoration. :undetermined is deliberately absent: it is an
   admission that the run could not look, and it is reported as such."
  #{:stale :inert})

(defn deviation-line
  "One line describing `dev` (a `deviation-status` map), or nil for nil.

   Each status names its OWN clause and says what to do about it, because
   these four reds arrive on mornings when the corpus changed and nobody
   touched the policy. A line that said only 'deviation' would need a triage
   to tell 'remove this' from 'pass :producers'.

   The :live line is not decoration either: it is what this check prints when
   it does NOT apply, and a check whose applies and does-not-apply outputs are
   the same string is not evidence. It also carries the disclosure the whole
   change is for — how many verdicts the global concession actually swallowed
   this run.

   WHICH NUMBER the :live line reports turns on the direction, and reporting
   the other one would be exactly the zero-bit line this file deletes
   elsewhere: a narrowing's :promoted is 0 by definition and a widening's
   :demoted is 0 by definition, so either would print the same digit on every
   possible run of its own kind."
  ^String [dev]
  (when dev
    (let [{:keys [kind status demoted promoted demoted-outcomes added-outcomes]} dev
          label (if (= :narrowing kind) "NARROWED" "WIDENED")]
      (case status
        :live (if (= :narrowing kind)
                (str "DEVIATION (NARROWED): demoted " demoted
                     " finding(s) the shipped default would have blocked.")
                (str "DEVIATION (WIDENED): blocks additionally on "
                     (vec (sort added-outcomes)) " — " promoted
                     " finding(s) failed this run that the shipped default "
                     "would have let through."))
        :stale (str "DEVIATION IS STALE: this policy stops blocking on "
                    (vec (sort demoted-outcomes)) " and this run produced NO "
                    "finding it demoted, so the concession bought nothing. "
                    "Remove it — the run is green under the shipped default. "
                    "This is the ratchet a :stale-exemption applies to a "
                    "scoped waiver, applied to a GLOBAL one.")
        :inert (str "DEVIATION IS INERT: no armed producer declares it can "
                    "emit any of "
                    (vec (sort (into demoted-outcomes added-outcomes)))
                    ", so this deviation could not have changed a single "
                    "verdict however bad the corpus was. Config that reads as "
                    "a decision and is not.")
        :undetermined (str "DEVIATION (" label "): UNDETERMINED — the armed "
                           "producer set was not supplied, so this run cannot "
                           "say whether the deviation demoted anything or "
                           "could ever demote anything. Pass :producers. "
                           "'I could not look' is not 'nothing to report'.")))))

(defn describe-policy
  "One line for the run header. A DEVIATING policy prints its whole proof on
   every run: the least a decision about what stops failing a run owes is a
   line in the log saying it was made, by whom, and when it lapses.

   IT NAMES THE DIRECTION, and that is a repair rather than a flourish. This
   printed `NARROWED` for ANY policy that differed from the default, so a
   policy blocking on MORE than the shipped set — the only kind of deviation
   the mode floor even permits on that axis — announced itself in the run
   header as a relaxation. The one line that discloses the deviation was
   capable of describing it backwards."
  ^String [policy]
  (str "verdict policy: fail-outcomes " (vec (sort (:fail-outcomes policy)))
       " fail-modes " (vec (sort (:fail-modes policy)))
       (if-let [dev (deviation policy)]
         (str " (" (if (= :narrowing (:kind dev)) "NARROWED" "WIDENED") " — "
              (:rationale policy)
              "; retires when " (:retires-when policy)
              "; owner " (:owner policy)
              ", expires " (:expires policy) ")")
         " (shipped default)")))

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

   A STALE OR INERT DEVIATION FAILS THE RUN ON THE POLICY'S OWN ACCOUNT, by
   the same argument and through the same seam as a malformed one: `:exit`
   reads `deviation-status` directly rather than inferring it from a count,
   because a deviation that has stopped earning its keep is most likely to be
   discovered on a clean corpus, which is precisely where a count-inferred
   verdict reads zero. It does NOT re-block the findings the way a malformed
   policy does — the concession is legitimate config that has gone stale, not
   an unreadable one, so relabelling every finding would misreport what is
   wrong. :blocking stays the findings; the deviation stands on its own.

   `opts` (optional) may carry `:producers` — the ARMED producer set. Supply
   it: neither `:not-exercised` nor a deviation's staleness is computable
   without it (see below), and a report that cannot compute them says so
   rather than guessing. Note what that costs on THIS path specifically: the
   ratchet cannot fire for a caller that omits the armed set, so the two-arity
   is honest and unarmed rather than green. `lanes/run-verdict` — the gate's
   own entry — always supplies it.

   Returns
     {:blocking      [finding …]   what fails the run
      :malformed     [{:finding f :problem s} …]
      :by-outcome    {outcome -> n}   every vocabulary key, zeroes included
      :by-mode       {mode -> n}      likewise
      :emittable     #{outcome …}|nil what the armed set CAN emit and block on
      :not-exercised [outcome …]|nil  in-scope blocking outcomes seen 0 times,
                                      nil when the armed set was not supplied
      :deviation     {…}|nil          `deviation-status`, nil on the default
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
                                o)))
         ;; An UNREADABLE policy has no readable deviation either: `deviation`
         ;; reads the two fail sets, which are exactly what a policy problem
         ;; says cannot be trusted. Reporting a deviation computed from them
         ;; would be a measurement against a policy the header just
         ;; disclaimed — the same refusal `emittable` makes one line up.
         ;; THE RAW VECTOR, not `well-formed`. `demoted-findings` owns the
         ;; malformed exclusion and states why; handing it the pre-filtered
         ;; vector would make that conjunct unreachable — a clause that can
         ;; never fire, which is the hazard the :passed refusal above exists
         ;; to name. Measured: with `well-formed` here, deleting the conjunct
         ;; left the whole suite green.
         dev (when-not policy-problem
               (deviation-status policy findings emittable))
         dev-problem (contains? deviation-problem-statuses (:status dev))]
     {:blocking blocked
      :malformed malformed
      :by-outcome by-outcome
      :by-mode by-mode
      :emittable emittable
      :not-exercised not-exercised
      :deviation dev
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

        ;; The deviation's own line, and it prints in EVERY state including
        ;; :live — see `deviation-line`. A global concession that discloses
        ;; itself only when it has gone stale is one nobody reads on the runs
        ;; where it is actively swallowing verdicts.
        (some? dev)
        (conj (deviation-line dev))

        (seq malformed)
        (into (for [{:keys [finding problem]} malformed]
                (str "MALFORMED (blocking): " problem " — " (pr-str finding)))))
      :exit (if (or policy-problem dev-problem (seq blocked)) 1 0)})))

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
