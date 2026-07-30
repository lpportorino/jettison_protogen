---
description: The two Malli populations in this repo's hand-authored Clojure — function specs that nothing checks and value schemas that throw — how to tell them apart, and what looseness costs in each. Loads when editing renderer-gen or protodoc Clojure.
paths:
  - "tools/renderer-gen/**/*.clj"
  - "docs/.protodoc/tools/**/*.clj"
---
<!-- LOAD-TEST: malli-schemas -->

# Malli here is TWO populations — one throws, one is prose, and the source does not tell them apart

This governs the INTERNAL Clojure schemas of these two trees. It does not reach
the `.proto` wire surface: proto shapes are additive-first and
backward-compatible by contract (`CLAUDE.md`), no Malli schema constrains them,
and nothing here licenses touching them. `tools/devcards` declares itself
malli-free in its own docstrings and is deliberately outside the frontmatter
above — do not widen the scope to it.

Both populations are written against `malli.core` and read alike at a glance.
Their effect is opposite. Mistaking one for the other is how a review ends up
citing a constraint that does not exist.

## `m/=>` IS DOCUMENTATION — nothing checks it

There is no arming seam. No `malli.instrument` / `instrument!` call exists
anywhere in this repo, so no spec is ever installed on a var and no call is ever
checked against one at runtime. Statically, `.clj-kondo/config.edn` maps
`malli.core/=>` to `clojure.core/comment` — a literal no-op. The auto-import that
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

**So never cite, review or reason about an `m/=>` spec as if something checks
it.** A wrong one reds nothing, which is precisely why it rots — and the reader
who quotes it as a constraint becomes the defect's next carrier. When a spec is
the evidence for a claim, go read the function body instead.

## The ENFORCED population is a CALL SITE, not an annotation

A value schema does work only where `m/validate` / `m/explain` is invoked and the
result is thrown on. Derive that set by grep rather than trusting a list — and
grep for the CALLER too, because the throw is often one hop away: the shipped
shape is a validator that returns the explain data and a pipeline entry point
that turns it into an `ex-info`. A validator whose own body only returns is still
enforced when its caller throws, and is enforced by nothing when the caller
merely logs. Check which before calling a schema a gate.

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
which in a population nothing checks is the worst possible outcome.

**WHAT THE GATE STILL CANNOT SEE, and this is the important half.** It judges that
a position NAMES a shape. It cannot judge whether the shape is TRUE of the
function, and the measurement above is the proof that nothing here can: with no
`instrument!` seam and `malli.core/=>` linted as a no-op, a spec that
mis-describes its function reds nothing. **So a green spec-shape run means every
position says something, never that anything it says is correct.** Reading it as
the latter is the false-green class `review-discipline.md` refuses, and it is the
specific reason tightening a naked spec is a SOURCE change requiring the function
body to be read — a precise-looking wrong schema is strictly worse than the honest
`:any` it replaced, because the next reader believes it.

Three things remain unenforced and are named rather than implied:

- **The enforced population's payload slots.** No gate reads a `m/validate` value
  schema for looseness, so the `[:tree [:map-of :keyword some?]]` class above is
  caught by review alone.
- **Spec PRESENCE.** A function with no `m/=>` at all is not a finding anywhere.
  That is a declared boundary, not an oversight: the count is large and
  `gate-enforcement.md` §1 refuses the baseline that would be needed to adopt it
  incrementally, so it is a project rather than a gate.
- **Whether a docstring or a spec is HONEST.** `lint-spec-shape` and
  `lint-docstrings` both check presence and shape. Neither can check truth, and a
  pass message that implied otherwise would over-claim.

For all three, the enforcement is the antagonistic review `CLAUDE.md` makes the
push gate.

The shorthand: **`m/=>` is prose and a call site is a gate; find the throw before
you call a schema enforced; and a slot that constrains nothing either says why or
comes out.**
