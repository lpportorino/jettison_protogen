---
description: The two Malli populations in this repo's hand-authored Clojure — function specs armed in one tree and prose everywhere else, and value schemas that throw — how to tell them apart, and what looseness costs in each. Loads when editing renderer-gen, protodoc, or scratchcard Clojure.
paths:
  - "tools/renderer-gen/**/*.clj"
  - "docs/.protodoc/tools/**/*.clj"
  - "tools/scratchcard/**/*.clj"
---
<!-- LOAD-TEST: malli-schemas -->

# Malli here is TWO populations — one throws, one is armed in a single tree, and the source does not tell them apart

This governs the INTERNAL Clojure schemas of these three trees. It does not
reach the `.proto` wire surface: proto shapes are additive-first and
backward-compatible by contract (`CLAUDE.md`), no Malli schema constrains them,
and nothing here licenses touching them. `tools/devcards` declares itself
malli-free in its own docstrings and is deliberately outside the frontmatter
above — do not widen the scope to it.

The three trees do not all carry both populations. `tools/renderer-gen` is the
only one with `m/=>` arrow specs at all, and the only one instrumented — see
below. `docs/.protodoc/tools` and `tools/scratchcard` carry the ENFORCED
population only: an explicit `m/validate` / `m/explain` call site that throws,
with no arrow specs anywhere in either tree. A rule read from the
`tools/renderer-gen` sections below about arrow specs or instrumentation does
not transfer to the other two; the ENFORCED-population sections do.

Both populations are written against `malli.core` and read alike at a glance.
Their effect is opposite. Mistaking one for the other is how a review ends up
citing a constraint that does not exist.

## `m/=>` IS ARMED IN ONE TREE AND PROSE EVERYWHERE ELSE — know which you read

**This section said the opposite until the claim was re-measured, so do not
trust a remembered version of it.** It read "there is no arming seam. No
`malli.instrument` / `instrument!` call exists anywhere in this repo" — and a
seam had landed since, leaving the file arguing from a premise its own later
sections contradict.

AT RUNTIME there IS a seam, and it covers exactly one tree.
`lvgl-codegen.instrument/arm!` calls `malli.instrument/instrument!` and is wired
as kaocha's `post-load` hook in `tools/renderer-gen/tests.edn`, so that suite
runs with every registered spec installed on its var;
`lvgl-codegen.spec-coverage` arms a second time to drive its own join. Measured:
`clojure -M:test` in `tools/renderer-gen` prints `[malli] armed 318 of 325
specced var(s) (7 refused, primitive-hinted) from 325 registered schema(s)` and
then `95 tests, 3755 assertions, 0 failures`. So inside that tree a spec is a
contract something checks wherever the suite reaches the function — and nowhere
else in this repo, because no other tree has a seam and no other tree carries a
spec at all.

STATICALLY nothing checks one, and that half is unchanged:
`.clj-kondo/config.edn` maps `malli.core/=>` to `clojure.core/comment` — a
literal no-op. The auto-import that
would supposedly restore type checking cannot supply it either: malli's own
exported clj-kondo config — the `config.edn` the malli jar ships as a
`clj-kondo.exports` resource — carries a `:lint-as` for `malli.experimental/defn`
and an `:unresolved-symbol` exclusion for `(malli.core/=>)`, and no type
annotations at all. Read that resource out of the jar rather than trusting the
parenthetical beside the `:lint-as` entry, which claims the opposite.

**Measured, with a control that proves the probe works.** A scratch namespace
declaring `(m/=> tight [:=> [:cat :int] :int])` and calling
`(tight "definitely-not-an-int")` yields ZERO findings under the flags `lint-clj`
passes (`lint.mk`), and zero again with malli's exported config handed in on
`--config-paths`. In the same file and the same run, `(inc "not-a-number")`
reports `error: Expected: number, received: string` and the run exits 3. So
`:type-mismatch` is live and the silence on the spec'd call is attributable to
`m/=>` itself — not to a dead linter, an unreached file, or a probe that never
ran. Re-derive it that way before believing any claim that the spec population
is checked; the control is the half that makes the null result mean anything.

**So never cite a spec as a STATIC constraint, and never cite one outside
`tools/renderer-gen` as a constraint at all.** No linter reds a wrong spec
anywhere, and outside that tree nothing reds one at runtime either — which is
precisely why such a spec rots, and why the reader who quotes it becomes the
defect's next carrier. Inside `tools/renderer-gen` the honest reading is
narrower still: a spec is checked on the calls the SUITE makes, so a spec on a
function no test exercises is checked by nothing. `lvgl-codegen.spec-coverage`
is the gate for that half, and it is enrolled over four namespaces. When a spec
is the evidence for a claim, go read the function body anyway.

## The ENFORCED population is a CALL SITE, not an annotation

A value schema does work only where `m/validate` / `m/explain` is invoked and the
result is thrown on. Derive that set by grep rather than trusting a list — and
grep for the CALLER too, because the throw is often one hop away: the shipped
shape is a validator that returns the explain data and a pipeline entry point
that turns it into an `ex-info`. A validator whose own body only returns is still
enforced when its caller throws, and is enforced by nothing when the caller
merely logs. Check which before calling a schema a gate.

`tools/scratchcard` is the clean illustration of the difference, in two files
that look alike at a glance and are not. `scratchcard.manifest/schema` is
`m/explain`ed and thrown on inside `validate!`, called before every manifest
write and on every read — a real, single-tree ENFORCED call site, added by
this tree and belonging to it. `scratchcard.input`, by contrast, never calls
`m/validate` or `m/explain` at all: it requires `malli.error` only to
`humanize` an explain map that a DIFFERENT tree already produced —
`lvgl-codegen.schema/validate-screen` in `tools/renderer-gen`, reached through
`lvgl-codegen.core/process-screen`. Reading `scratchcard.input`'s malli import
as a second enforced population in this tree would be the "trusting a list"
mistake the paragraph above warns against; grep for where `m/validate` /
`m/explain` is actually CALLED, not for where `malli.*` is required.

## Looseness is a different defect in each population

A spec whose entire content is the ABSENCE of a constraint — `:any`, an
unqualified `[:map-of :keyword :any]`, an `[:or …]` with no tag telling its arms
apart — is worth nothing in the documented population and worse than nothing in
the enforced one.

- **Documented population**: its only reader is human, so a spec naming no arm
  set costs maintenance and returns nothing. Make it say something, or delete it;
  a placeholder that survives review teaches the next author that the shape was
  considered and found to be anything.
- **Enforced population**: it is a vacuous slot inside a strict-looking gate, and
  the strictness around it is what makes it dangerous — the envelope reads
  validated, so nobody re-checks the payload.

**Live instance.** `lvgl-codegen.schema/components-file-schema` is
`[:map {:closed true} …]` with bounded strings, and `lvgl-codegen.core` throws on
its explain: a real gate, correctly built. Its `[:tree [:map-of :keyword some?]]`
slot is the part that actually drives codegen, and it admits any non-nil value.
"components.edn is shape-validated" is therefore true of the envelope and false
of the payload, and the closed-map spelling beside it is what makes the gap
invisible.

**The sanctioned form states its reason.** `lvgl-codegen.emit-proto`'s
`expanded-widget` is `[:map [:tag keyword?]]` whose docstring says the remaining
keys are optional and value-heterogeneous, so the map is open beyond `:tag`. An
EDN- or deserialization-boundary scalar sum where the heterogeneity IS the
contract is legitimate on the same terms — say so in the schema, where the next
reader meets it, never only in a commit message.

The discriminator is whether a reader learns the shape from the schema. Openness
that names what it declines to constrain teaches; `:any` teaches nothing and
reads as an oversight, so the next author either tightens it wrongly or trusts it
wrongly.

## Arming the documented population makes it a gate

Arming is ONE `instrument!` at a seam the trees already pass through, never a
call per entry point — a caller can forget, a seam cannot
(`gate-enforcement.md` §4). The moment it is armed the spec population is a gate
and owes `gate-enforcement.md` §2 in full: a constructed known-bad input it
rejects, a FAIL rather than an ERROR, attribution to the clause under test, and
proof the mutation landed.

Two things to expect, because they run opposite to the intuition. **The loose
specs will keep passing** — `:any` accepts everything, so arming does not surface
them and cannot be the mechanism that cleans them up. **The specs that go red are
the ones that mis-describe their function**, which is the likely state of any spec
nothing has ever checked; arming is therefore a source change with an unknown
blast radius, not a switch.

### ARMING SILENTLY DISARMS EVERY NEGATIVE-PATH TEST — this is the sharp edge

A function that VALIDATES ITS OWN INPUT and throws cannot have a tight `m/=>`
input schema and still be negative-tested through its var. Instrumentation
rejects the bad argument BEFORE the body runs, so the guard under test never
executes — and malli's rejection is itself a `clojure.lang.ExceptionInfo`, so
`(is (thrown? clojure.lang.ExceptionInfo …))` cannot tell the two apart.

**The test keeps passing with the guard DELETED from the function.** That is a
green asserting the opposite of what it claims, and nothing about it looks wrong.

Measured on `lvgl-codegen.palette-ladder/hex->rgb8`, whose spec is
`[:=> [:cat [:re hex-pattern]] [:vector :int]]` and whose body throws
`"not a canonical #RRGGBB hex"`. It was the ONLY spec in this tree to report a
violation under instrumentation, and the three violating calls were all
deliberately-invalid arguments from its own negative-path test — not a defect,
and not a mis-described spec. Instrumentation had found the test, not the code.

So the repair belongs in the ASSERTION, and it is owed BEFORE arming, not after:
match the MESSAGE (`thrown-with-msg?`) so the function's guard is
distinguishable from malli's wrapper. `palette_ladder_test.clj` now does, and the
pair of runs is the proof — it passes uninstrumented, and goes RED under
instrumentation with `:malli.core/invalid-input` as the cause. Red is the
CORRECT outcome there: it says out loud that instrumentation is intercepting a
call the test needed to reach the body. A bare `thrown?` said nothing in either
direction.

The general rule: before arming, every `thrown?` on a self-validating function is
suspect, and a test that cannot name WHICH layer refused is not evidence about
either. And it inherits `gate-enforcement.md` whole,
exemption shape included: an exemption key invented inline in a schema would be a
second exemption vocabulary competing with the one this repo already has, which
is the silent fork these rules refuse everywhere else.

## ONE HALF OF THIS FILE IS NOW A GATE — and know exactly which half

`make -f lint.mk lint-spec-shape` (`lint-gate.specs`) reads every `m/=>` form in
the enrolled trees and REFUSES a bare `:any`, `:map` or `:string` in an argument or
return position. It carries the whole `gate-enforcement.md` §2 bar: mutation-proven
clause attribution, a non-vacuity floor over the SPECS (a zero population is
CANNOT-RUN, not a perfect score), an unparseable file reported as a finding rather
than skipped, and the proof-carrying exemption contract with staleness.

`:keyword` and `:int` are deliberately NOT refused. For a function whose argument
genuinely is an arbitrary keyword, `:keyword` is the tight answer, and a gate that
pushed authors toward something narrower would be manufacturing false schemas —
which in a population checked in only one tree, and there only where the suite
reaches, is the worst possible outcome.

**WHAT THE GATE STILL CANNOT SEE, and this is the important half.** It judges that
a position NAMES a shape. It cannot judge whether the shape is TRUE of the
function, and the measurement above is the proof that no GATE here can: outside
the one instrumented tree there is no runtime seam at all, and inside it the
check reaches only functions the suite actually calls — so a spec that
mis-describes its function reds nothing that a gate reports. **So a green
spec-shape run means every
position says something, never that anything it says is correct.** Reading it as
the latter is the false-green class `review-discipline.md` refuses, and it is the
specific reason tightening a naked spec is a SOURCE change requiring the function
body to be read — a precise-looking wrong schema is strictly worse than the honest
`:any` it replaced, because the next reader believes it.

**SPEC PRESENCE IS NOW A GATE TOO, over a declared scope.** This file used to
record presence as "a project rather than a gate", on the ground that §1 refuses
the baseline needed to adopt it incrementally. The first half of that is still
right — a baseline, a percentage floor and a parked-findings list are all
refused, and none of them is what landed. `make -f lint.mk lint-spec-presence`
(`lint-gate.presence`) instead takes §1's OTHER permitted move: narrow the
declared scope to a population where the check passes, say what was left out,
and state the measured finding count — which the GATE states, on every run.
`:enrolled` in `tools/lint/gates.edn` names NAMESPACES rather than roots,
because no root qualifies: only `tools/renderer-gen/src` practises arrow specs
at all, it is not itself at 100%, and every other gated root —
`tools/scratchcard/src` now among them — is at 0.0%, so a root-grain scope would
have to be empty. No fraction is quoted here on purpose; the two obvious
candidates are not even the same quantity (`spec-presence` counts FUNCTIONS
carrying a spec, `spec-shape` counts `m/=>` FORMS it can read), so a pair copied
into prose reads verified while comparing different denominators. Inside an
enrolled namespace the
check is TOTAL with zero tolerated misses and zero waivers, and it prints the
judged fraction of the whole gated corpus (`LINT_CLJ_PATHS` in `lint.mk`) on
every run so a green cannot be misread as tree-wide. That corpus grows on its
own schedule — three new gated paths landed with `tools/scratchcard` and none
of them practise `m/=>` — so read the fraction from the gate's own output
rather than from a count frozen here.

Three things still remain unenforced, and are named rather than implied:

- **The enforced population's payload slots.** No gate reads a `m/validate` value
  schema for looseness, so the `[:tree [:map-of :keyword some?]]` class above is
  caught by review alone.
- **Presence OUTSIDE the enrolled namespaces.** Every specced function sits in
  `tools/renderer-gen/src`, and the enrolled namespaces above account for only
  part of even that root, so most of the gated corpus is unspecced AND
  unenrolled at once. `make -f lint.mk lint-spec-presence` prints the exact
  split on every run; a count frozen here would already be wrong, because the
  gated corpus (`LINT_CLJ_PATHS` in `lint.mk`) grows independently of the
  specced one — `tools/scratchcard` joined it carrying zero `m/=>` forms. That
  remainder is a project, not a tolerated miss count — the difference is that
  no list of it exists anywhere a gate reads, and the way to shrink it is to
  bring a namespace to 100% and ENROL it, never to widen anything.
- **Whether a docstring or a spec is HONEST.** `lint-spec-shape`,
  `lint-spec-presence` and `lint-docstrings` check presence and shape. None can
  check truth, and a pass message that implied otherwise would over-claim.

For all three, the enforcement is the antagonistic review `CLAUDE.md` makes the
push gate.

The shorthand: **`m/=>` is armed in one tree and prose everywhere else, and a
call site is a gate; find the throw before
you call a schema enforced; and a slot that constrains nothing either says why or
comes out.**
