# Antagonistic review — two lenses at once, evidence over sentence, a red that names its clause

`CLAUDE.md` §"Fixing protogen from a consumer" makes the review the gate: nothing
mechanical gates a push here. This file is what that review has to BE to deserve
the name, and what to do with a finding that arrives outside one.
`.claude/rules/fork-isolation.md` §"Lifting" applies the same discipline
to work coming back from a fork; it is not a different standard.

## A TAPER IS NOT CONVERGENCE

Measured on one commit: three successive rounds by one reviewer returned 4, then
2, then 2 findings. That shape reads like a diff running out of defects. Round
four ran TWO reviewers with DIFFERENT lenses at once and returned 17 — including
three the taper had walked straight past. **A falling find-rate measures the
reviewer running out of ideas, not the diff running out of defects.** Two lenses
at once cost one round and found more than the three single-lens rounds combined.

So run the lenses concurrently, and never read round N+1's smaller number as
evidence that round N converged.

## THE SECOND LENS THAT PAYS IS A PURE FACT-CHECK

Not "is this well designed" a second time. A fact-check lens verifies every
CHECKABLE assertion — in the diff AND in the commit message — against what is
actually in the tree: paths, symbols, make targets, quoted code, counts,
attributions. It is mechanically different work and it finds a disjoint class.

Measured on one batch of repairs (`fix(devcards): gate the truncation membrane
where its own push can reach it`): three false claims, none of them a design
error.

- A docstring count of `:probe-pixel-only` corpus cards that was one too high,
  because the last grep hit was the legend entry naming the keyword. The repair
  is to DELETE such a count, not to correct it — a re-derivable number does not
  belong in prose (`.claude/rules/claude-md-policy.md`).
- A rule claiming the doc tree had "two local gates" when `check-renderer` runs
  neither, so a green local battery said nothing about that tree in either
  direction. `.claude/rules/devcards.md` carries that boundary.
- An inline code span folded mid-identifier — `` `devcards.` `` at end of line
  and `` overlap` `` on the next — which renders a broken symbol and loses the
  grep. A design read cannot see this; a fact-check read trips on it at once,
  because the symbol it went to look up does not resolve.

**Reading finds a wrong CLAIM; only running finds a RED.** A fact-check pass over
nine commits verified every message claim against the tree and still missed a
cljfmt drift that would have turned CI's Lint job red on push — it read sources
and never ran `make -f lint.mk lint`. Three forks briefed to RUN the gate each
caught it. Before trusting that a mechanical gate would have caught something,
check it is installed: `make -f lint.mk hooks-status`. That hook being unarmed is
how the drift reached master unseen.

## APPLY A REVIEWER'S EVIDENCE, NEVER THEIR SENTENCE

A finding's MEASUREMENT is what transfers. Its PROPOSED WORDING is a hypothesis
about the fix and owes the same re-derivation as any other draft. Verify the
finding, write the fix yourself, then check the fix — three steps, not one.
Landing the sentence verbatim has repeatedly imported an error the evidence did
not contain.

**The reviewer's evidence can be exact while its inference is inverted.** A
review reported, from correct coordinates, that a roller stops centring its
selection at a list boundary; the drafted correction would have DOCUMENTED A
LIMITATION THE CODE DOES NOT HAVE. `get_sel_area` in
`renderer/lvgl/src/widgets/roller/lv_roller.c` computes the band from the
roller's own coords and font metrics with no selected-option term at all — the
label is the SCROLLED content, so it must move one row per index step for the
selection to stay put. Re-derived and measured in `test(devcards): render the
disabled roller at both list boundaries`.

**And a correction overshoots into the opposite falsehood**, which is harder to
catch because it reads as modesty. Repairing an over-claim about which producers
ship produced "only overlap and the layer contract have producers" — which erases
the armed `:zero-visible-area` check in `tools/devcards/src/devcards/invariants.clj`.
When retracting "X exists", the replacement is almost never "X does not exist":
write the boundary.

## A SURPRISE IS A FINDING THAT ARRIVED WITHOUT A REVIEW

When the tree contradicts your expectation mid-task — a golden that moved when
nothing should have shifted a pixel, a lint red in a file you did not touch, a
symbol not where a rule said it was — you have been handed, early and free, the
same object the fact-check lens above is run to produce. **Disposition it.**
Absorbing it is the one move that costs more later, and "noting it for later"
while pressing on is absorption. How far it preempts the in-flight task is
already ranked by `.claude/rules/work-prioritisation.md`; the only sanctioned
deferral is one said out loud.

Localise before fixing, into exactly one of three:

- **the code is wrong** → fix it at the root, never at the call site that met it;
- **the doc, rule or NAME that shaped the expectation is wrong** → it
  manufactured the surprise and IS the defect. This is the common bucket here,
  because so much of this repo is prose about itself: the corpus-secret-scan
  bullet in `.claude/rules/devcards.md` is a landed instance — the gate receives
  card maps only, and what needed correcting was the rule sentence that read as
  covering every string in the corpus file;
- **nothing was written** → the contract was implicit and a careful reader was
  bound to trip on it. Write it where the tripping happened.

**Prefer the fix that removes the special case to the one that adds a guard** —
the bar is that the class cannot recur, not that the symptom went. And **make
both sides agree in the same commit**: code fixed with the sentence that
described the old behaviour left standing is the next surprise, already queued.

**"Flaky" is not a disposition available on the deterministic lanes.** A golden
is sha256 over raw framebuffer bytes and `composition_cross_engine_fb` asserts
wasmtime and GraalWasm byte-identical, so there is no tolerance band for a
difference to be absorbed into: non-determinism there is a defect with no second
reading, and re-running until green destroys the only evidence it existed.

## DEFECTS HIDE INSIDE THE PREVIOUS ROUND'S REPAIRS

Every round here has found defects the round before it introduced. A round that
finds nothing new in the original diff is therefore not finished — it has not
reviewed the repairs yet, and the repairs are written under time pressure by
someone who has stopped expecting to be wrong. Two landed instances name
themselves: `docs(lint): correct my own correction — the hook never calls
lint-c-tidy`, and `docs(devcards): name the right subject, and stop claiming an
impossibility` (a third round: no blocker, three factual errors, all in prose the
previous round had just repaired).

The worst of them was inside a canary. The repair asserted that the text
`"truncated":true` appeared in the normalized output — which the renderer's own
overflow sentinel already contains, so it passed with the membrane deleted. The
canary that replaced it asserts the result PARSES, with the raw bytes asserted
unparseable FIRST (`tools/devcards/test/devcards/host_test.clj`) — the contract
rather than a substring of it. **A fix to a canary is the last place to skip the
mutation.**

## A RED PROVES NOTHING UNLESS IT CAME FROM THE CLAUSE UNDER TEST

`CLAUDE.md` requires every gate to carry a canary that fails for ITS OWN reason.
The review-side consequence is that you cannot take a reviewer's red — or your
own — on its colour. The convention here is a
`REVERT-TO-BREAK:` line naming the exact production expression to revert, paired
with a CONTROL that must stay green so the survivor is attributable to the clause
and not to a neighbour (`tools/devcards/test/devcards/invariants_test.clj`).
Reviewing a canary means RUNNING that revert, not reading it.

**A REFUSAL SHOWN IS NOT A REFUSAL ATTRIBUTED**, and this is the form the demand
usually takes when it is under-specified. Asking a worker to "prove the guard
fires" is satisfiable by any red — including one produced by a NEIGHBOURING
clause that would have refused the same input for a different reason. Measured
while hardening `tools/claude/forks.sh`: of four guards asked to be demonstrated,
three had a neighbour that also refused, and one input tripped two clauses at
once. Only a mutation pass — break THAT clause alone, leave the neighbours
intact — separates them, and it is what surfaced a defect the four demonstrations
walked straight past: a bare `return` propagating a failed test's status under
`set -e`, aborting with exit 1 and no message.

So the demand is not "show me a red". It is **"break this clause and show me the
message that names it"**, plus a control proving the neighbours stayed green.
Where the guard's own diagnosis can be wrong — an internal error mistaken for a
clean verdict — the canary must distinguish those too: exit codes that separate
FAIL from ERROR are how, and a suite asserting only non-zero cannot.

**Require a FAIL, not an ERROR.** A mutation that breaks compilation or the
namespace load reds the whole file while executing nothing, so the red carries no
information about the clause. Measured, and it is why one canary was deleted
(`test(protodoc): delete three canaries measured unable to fail`): renaming a var
to "prove" a namespace-resolution canary produced `Syntax error compiling … No
such var` in the canary's OWN namespace — it never ran.

**A green is not proof the body ran either.** The same commit measured a suite
printing `Ran 233 tests containing 0 assertions. 0 failures, 0 errors.` and
exiting 0 with every test body suppressed. All three deleted canaries used raw
`throw` rather than `is`, so they contributed ZERO assertions to the tally the run
is judged by. Read the assertion COUNT, not the colour.

**AND `clojure -M:<alias> -e '<form>'` DOES NOT EVALUATE THE FORM when the alias
declares `:main-opts`.** Trailing tokens after `-M:<alias>` are APPENDED as ARGV to
that alias's `-m` main, so the form is handed to a test runner as an argument
instead of being evaluated. A probe written that way never existed, and what the
run reports is whatever the runner did with an unknown flag.

**The two runners in this tree behave OPPOSITELY, so checking one proves nothing
about the other** — which is what makes this worth writing down rather than
learning twice. Measured, from each tree's own root:

- `tools/renderer-gen` (`:test` → `kaocha.runner`) REFUSES the unknown flag: it
  prints its help and exits 255. Loud, and harmless.
- `docs/.protodoc/tools` (`:test` → `cognitect test-runner`) IGNORES it: the whole
  suite ran, printed `Ran 236 tests containing 23659 assertions. 0 failures, 0
  errors.` and exited **0**, while the `-e` form never printed. A perfect green
  over a probe that did not run.

So a reviewer who tests the hazard against kaocha concludes it is safe, and is
wrong about the other tree. The tell is structural rather than empirical: check
whether the alias declares `:main-opts`. `docs/.protodoc/tools/aot.sh`'s
`clojure -M:aot -e "(require 'protodoc.core)"` DOES evaluate, precisely because
`:aot` declares none — and the `Makefile`'s `clojure -M:aot:run generate …` is the
legitimate use of the append behaviour, not a bug.

The safe forms are already in this tree and are what to copy: a throw-away alias
via `clojure -Sdeps '{:aliases {:probe {:extra-paths ["dev"]}}}' -M:probe <file>`
(the devcards and renderer-gen probe drivers), or `CP="$(clojure -Spath)"` and a
direct `java` invocation (`compile-bindings.sh`, `compile-protos.sh`).

**A canary aimed one layer off PASSES**, and its green is indistinguishable from
the green of one that reached the code the way callers do — it tests your MODEL
of the fix. Two tells, either alone sufficient: it asserts on a value it
constructed rather than on one the production path returned or stored; or its
assertions would still hold with the change reverted. The `"truncated":true`
repair above is a measured instance of the second. So ask the falsifying
question BEFORE the canary lands — *which line goes red on the revert?* — and its
pair, *what does this print when it does not apply?* A check whose broken output
and whose nothing-to-report output are the same string is not evidence.

The first tell has a specific shape here: **a canary whose fixture is a
hand-written dump-tree map asserts the author's model of the dump vocabulary**,
and that vocabulary's hazard is which keys `dump_obj` OMITS (`click_area` only
when it differs from coords, `descend_gate` only when OVERFLOW_VISIBLE grows it;
`CLAUDE.md` §"Consuming the UI standard" carries which way each absence fails).
`devcards.deadzone` is the shape to copy: `deadzone_test.clj` pins the reducer
and registry seams on hand maps and says in its own docstring that this is all
it does, while `deadzone-canary-prebuilt` plants cases through
`fixtures/build-authored-card` and renders them on the real wasm, armed in
`check-renderer`. `devcards.overlap` has no such partner — its positives are
hand maps only, and the `class-census` alias that runs the rule over the real
corpus gates nothing. `.claude/rules/renderer.md` discloses the same gap for the
wire-number LUTs in the same honest shape; naming it is the requirement, not
having closed it.

**A missing PREREQUISITE that only warns turns every later green into
decoration.** Classify at the seam where the component is acquired, never at each
caller — a caller can forget, a seam cannot — and the test is *is any claim this
run produces still true without it?* If no, its absence must FAIL.
`lint.mk`'s `lint-ci` tool-presence guard is the correct POSITIVE case: actionlint
is required, its absence would leave workflow syntax unjudged, so the recipe fails
with an install hint rather than skipping — absence changes a claim, so absence
fails.

The live COUNTER-example is `generate-protos.sh`'s rust leg, which prints
`WARNING: Skipping Rust generation` and `exit 0` when cargo is absent or its
directories are unwritable. The run then reports success while `output/rust` was
never regenerated, so "this run regenerated the bindings" is false and nothing
says so. It is the same leg whose `cargo build 2>&1 | tail -5` reports `tail`'s
status rather than the build's, because each leg is dispatched as a quoted script
into a fresh shell that re-arms `set -e` alone — no `pipefail`, no `nounset`. Two
independent ways for one leg to pass having done nothing. Naming it is the
requirement, not having closed it.

Two more ways a red or green misattributes itself:

- **A mutation that did not land looks exactly like a canary that did not fire.**
  `.claude/rules/fork-isolation.md` §"Lifting" carries the habit — assert the
  mutation landed before believing any result — and it is not lift-specific.
- **A canary whose trigger paths exclude the paths that can break it is armed in
  name only**, and every run of it is green for a structural reason
  (`ci(devcards): arm the dead-zone canary where its own source can trigger it`).

## A REDUNDANCY AUDIT IS STRUCTURALLY BLIND TO AN ABSENT GATE

"What here is duplicated" has no term for "what is missing", so ranking merges by
cheapness recommends destroying coverage while the largest hole goes unlisted.

Measured: a gate audit inventoried four slices — renderer-battery, ci-and-lint,
devcards-lanes, test-suites — and ranked its MERGE section by runner seconds
saved. The repo's biggest gate-shaped hole, that nothing distinguished a breaking
proto change from an additive one, appears nowhere in it. A completeness critic
found it by asking *what did you not look at*, and the answer was the proto
pipeline, the fan-out and the docs lane — the things this repo actually ships.
The finding is not that the audit was bad; it was excellent. **The shape of a
search determines the shape of what it cannot find**, so every audit owes that
second question, asked by someone who did not design the first.

What closes that hole is not the obvious fix:
`.github/workflows/wire-contract.yml` asserts `docs/INTERFACE-CONTRACTS.md` §9's
vectors against the descriptor set, and its own header records why it is NOT `buf
breaking` — renumbering is deliberately permitted here when consumers rebuild in
lockstep, so a hard breaking-change gate would fight a stated policy. The obvious
fix is the one most likely to have skipped the policy it contradicts.

The shorthand: **two lenses at once, one of them a pure fact-check against the
tree; run the gates rather than reading them; take the evidence and write the fix
yourself; disposition a surprise instead of absorbing it; review the previous
round's repairs; and make every red name the clause it came from — through the
path the callers use.**
