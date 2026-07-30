# What makes a gate a gate here — it must pass, and it must be able to fail

`.claude/rules/lint-gates.md` says what the format/lint lanes ARE and where each
runs. `.claude/rules/review-discipline.md` says how to review a change and how to
attribute a red. This file is the ENFORCEMENT BAR every gate in this repo is held
to, whatever it checks and whatever language it is written in — and it binds a
gate the day it is added, not later.

protogen is the pinned upstream for a consumer fleet, so a gate here is not local
hygiene: a defect that gets past it is republished to every repo that rebuilds
from the pin, and a gate that silently stopped checking is indistinguishable from
one that has nothing to report.

## 1. EVERY FORMATTER AND LINTER MUST PASS. There is no third state.

A finding has exactly two permitted dispositions: **FIX IT**, or **RECORD AN
EXEMPTION FOR A TRUE FALSE POSITIVE**. Nothing else. In particular there is no
advisory tier, no "warning", no "fix later", and no finding that is merely
counted.

- **A warning IS a failure.** A command that prints findings and exits 0 leaves
  CI green and forces no disposition, so findings accumulate and a real defect
  hides among them in plain sight — the tool reports both identically. Where a
  tool has a warning tier, the gate invokes it so warnings block.
- **We do not hide issues; we solve them.** If a check is right and the tree is
  wrong, the tree changes.
- **Never widen a check to make a tree pass.** Loosening a threshold, adding an
  extension to an ignore list, or relaxing a pattern until the red goes away
  destroys coverage to buy a colour. The permitted moves are: fix the finding,
  exempt the specific finding, or DECLINE THE CHECK ENTIRELY with the reasoning
  recorded. Declining on the record is honest; quietly weakening is not.
- **If a rule is genuinely wrong for this repo, delete the RULE**, with its
  reasoning, where the rule is configured. That is a decision on the record. A
  silenced symptom — an inline suppression comment, a per-finding ignore, a
  widened allowlist — is not, and is forbidden by `lint-gates.md`.

### SCOPE may be declared. FINDINGS may not be parked.

These two get confused, and the difference is the whole of this section.

**Declaring scope** says which population a check judges: hand-authored source
and not generated or vendored trees; contract surfaces and not verification
surfaces. It is a statement about what the check MEANS, it is written down with
its reasoning, and inside that population the check is total. Legitimate.

**Parking findings** grandfathers real defects the check found — a baseline file
of known misses, a "current count" the gate compares against. The gate then
reports green while the defects it exists to catch sit inside it, and the number
becomes a target nobody ever drives to zero. **Not legitimate here**, however
carefully the baseline is generated.

### A METRIC CEILING IS NOT A PARKED FINDING — the discriminator is enumerability

These two look alike because both start from a measurement of today's tree, and
conflating them would forbid the one honest way to adopt a size or complexity
bound. The question that separates them: **does the committed number enumerate
individual findings that a fix removes ONE AT A TIME?**

- **A list of individual findings** of a binary check — this var has no docstring,
  this expansion is unquoted, this call site is banned. Each entry is a defect
  with an unambiguous fix, and the list is the suppression. Parking. Refused.
- **A single number bounding a continuous metric** — lines, public vars,
  complexity, nesting. There is no per-item entry to grandfather, and no "true"
  threshold to be widened away from: the threshold IS the judgement, and it is
  being made for the first time.

A metric ceiling is legitimate only with all three of: it is seeded so the gate
is green **with ZERO exemptions** (a ceiling that still needs waivers has not
been seeded, it has been guessed); it moves **DOWN only**, and raising one is a
gate bypass in source form; and a **stricter tier is reported every run** as a
non-blocking watchlist, so the distance between today's worst and where the bar
should be stays visible instead of being quietly redefined as fine.

Seeding at the measured maximum is then the honest choice over adopting a
borrowed threshold that lands red, because the alternative is a dozen waivers
whose `:rationale` would have to be invented — and an invented rationale is worse
than an accurate number, since it corrupts the one field the whole exemption
machinery relies on being true.

Record the measured provenance beside each number, so a later reader can tell a
seeded ceiling from an arbitrary one. Without it the two are indistinguishable,
and the next author raises it.

The consequence is a real constraint on adopting a check, and it must be faced
rather than routed around: when a check cannot pass on this tree, the honest
outcomes are to FIX the tree, to NARROW THE DECLARED SCOPE to a population where
it does pass and say what was left out, or to DECLINE the check and say why —
each with the measured finding count stated. "Land it against a baseline" is not
on that list.

### An exemption is a WAIVER, and it carries proof

An exemption is for a TRUE false positive only — the check is wrong about this
specific case. "Too noisy", "too many findings", and "we will get to it" are not
reasons.

- Scoped as **narrowly as the tool allows**. A whole-file skip where a
  single-line entry would do is a silent scope reduction.
- Carrying all four of `:rationale`, `:retires-when`, `:owner`, `:expires`, each
  a non-blank value — the set `devcards.invariants/exemption-proof-keys` names,
  read from there rather than re-spelled. `:rationale` is why this is not a
  defect; `:retires-when` is the EVENT that makes the entry unnecessary, which no
  date can express; `:owner` is who to ask; `:expires` is the outer bound at
  which the decision is re-taken whether or not that event happened.
- **A stale entry — one matching no live finding — is itself a hard failure.**
  Without that, an entry outlives the finding it excused and silently widens the
  unchecked surface, and the ratchet stops being down-only: an offender can be
  fixed while its excuse stays behind. This is the clause that makes an
  exemption list shrink over time instead of growing forever.

## 2. EVERY GATE CARRIES A CANARY THAT PROVES IT CAN FAIL

A gate nobody has watched fail is a prescription, not a gate. Green is
uninformative until failure has been demonstrated, because the commonest way a
gate dies is by silently checking nothing — and that state emits exactly the
output a clean run does.

So a gate lands with a canary, in a suite something RUNS, and the canary owes
four things:

- **A known-bad input that the gate REJECTS.** Constructed, named, and kept — not
  a red observed once by hand and then discarded.
- **A FAIL, not an ERROR.** A check that dies on a broken harness, an unparseable
  config or a missing namespace wears the right colour for the wrong reason and
  proves nothing about the clause. Give a gate exit codes that SEPARATE a verdict
  from a precondition failure, and have the canary assert the exact code — a
  suite asserting only "non-zero" accepts a syntax error as proof that a clause
  fired.
- **ATTRIBUTION TO ITS OWN CLAUSE.** A refusal shown is not a refusal attributed:
  most gates here have several clauses that would refuse some of the same inputs,
  so a red is compatible with the clause under test being dead. Break THAT clause
  alone, require the gate to go green, and require a NEIGHBOURING clause to still
  refuse on that same mutant. `review-discipline.md` carries the method and the
  traps; this file makes it a condition of the gate existing.
- **PROOF THE MUTATION LANDED.** A mutation that silently matched nothing yields
  a mutant identical to the original, and its green then reads as attribution
  while proving the exact opposite. Assert the new text present AND the old text
  absent, on the exact bytes, before believing any colour. Beware line-oriented
  tools for this: a multi-line anchor handed to a line-matcher tests each line as
  a separate alternative, so the assertion succeeds on any surviving fragment.

**Prefer a synthetic fixture to the live tree.** A canary that perturbs tracked
files cannot run on a dirty checkout, entangles itself with whatever else is in
flight, and has expectations that drift whenever the repo moves.

**And point the trigger at the paths that can break it.** A canary whose CI
filter excludes the files whose change would break it is armed in name only, and
every run of it is green for a structural reason. A gate whose input set is the
whole tree cannot sit behind a path filter at all.

## 3. AN EMPTY INPUT SET IS A FAILURE, NEVER A PASS

For most gates the value printed on success and the value printed after
discovery collapses are the SAME: nothing to report. So a gate whose file
discovery, glob, or coverage denominator comes back empty must FAIL LOUD rather
than pass — a green tick over zero coverage is the worst output a gate can
produce, because it actively asserts what it never looked at.

- Discovery is **explicit**, so it can be guarded. A tool left to find its own
  inputs and exit 0 on finding none cannot be floored.
- **Capture the discovery command's stderr and print it.** When discovery breaks,
  the reason IS the diagnosis; guessing at a cause in a static message points the
  reader at the wrong thing with confidence.
- **The diagnosis must ask the same question as the discovery.** A probe that
  drops a flag the real call passes is a different experiment, so a fault specific
  to that flag yields an empty file list AND an empty diagnosis.
- A **coverage denominator** of zero is the sharpest form, because the vacuous
  value is a PASSING one: no population means no misses, so the gate prints a
  perfect score for having measured nothing. Floor it.
- Floor each root **individually** where a gate walks several. Any populated root
  satisfies a union floor, so one root going dark is invisible.

Honest scope, so no pass message over-claims: a floor of one proves the
population is non-empty, never that it is the RIGHT population. A filter narrowed
to a rare form still scores perfectly against the handful it can still see.

## 4. A MISSING TOOL IS A HARD FAILURE WITH AN INSTALL HINT

A gate that reports success because its tool is absent is the same defect as one
that passes on an empty input set, and it is easier to reach: a fresh checkout, a
container without the toolchain, a runner whose install step was reordered.

- Check for the tool and **fail** when it is missing. Never skip, never warn-and-
  continue.
- **Say how to get it.** A bare `command not found` names the wrong problem: the
  reader sees a broken gate rather than a missing dependency, and the exit code
  is indistinguishable from a harness error.
- Classify at the **seam where the tool is resolved**, not at each call site. A
  caller can forget; a seam cannot.
- The test for whether absence may be tolerated is: **is any claim this run
  produces still true without it?** If no, its absence must fail. The sanctioned
  negative case is a tool whose absence changes no verdict — a request-budget
  token, a cache — where warn-and-continue is correct precisely because the
  degradation it risks fails closed elsewhere.

Where a tool REWRITES committed source, its version is part of the toolchain pin
and an unpinned copy is worse than none: it rewrites the tree into something CI
rejects, manufacturing the drift the gate exists to catch. Resolve the pinned one
or drop to check-only — and say which happened.

## 5. A GATE IS WRITTEN IN A LANGUAGE THIS REPO GATES

**The enforcement of quality may not itself be unenforced.** A gate written in a
language no lane judges is the least gated code in the tree, and it is the code
whose correctness every other verdict depends on.

What is judged here, and therefore what a gate may be written in:

| language | what judges it |
|---|---|
| Clojure | cljfmt, clj-kondo at a zero-warning floor, the namespace-size ceiling |
| shell | `bash -n` and the payload-apostrophe check, over discovered scripts |
| C | clang-format drift-compare and clang-tidy, both pinned |
| GitHub Actions | actionlint |

Any other language is judged by NOTHING, and a syntax floor is not a linter — a
gate that merely parses is not thereby checked. Prefer Clojure for anything with
structure and shell for anything that is mostly discovery and process plumbing;
place the source where the lane can reach it, which for Clojure means inside
`LINT_CLJ_PATHS` and not merely somewhere under `tools/`.

**Self-gating is a real property, not a gesture, and it earns its keep
immediately**: the first clj-kondo run over a gate moved into that set reported a
dead private var inside the gate — a finding that had been invisible for as long
as the gate lived outside every lane.

Two consequences worth stating, because both have been reached for:

- **A dependency is not the price of admission.** A structural gate can read
  clj-kondo's analysis as EDN and parse it with `clojure.edn` from the standard
  library, so the alias declares no `:extra-deps` at all. A gate that drags in a
  library becomes one more thing to keep resolving, in the lane that must work on
  a fresh checkout.
- **Config is EDN for the same reason the code is Clojure.** It is the format this
  repo already reads and already filters on, so a ceiling file or an exemption list
  in EDN is covered by the same path filters as the code it governs.

The rule binds the GATE, not the repository. A research probe, a one-off
measurement or a vendored reference implementation may be in whatever language it
was written in — those produce findings for a human to read, never a verdict
something depends on. The line is whether anything is GATED on its output.

## 6. WIRE IT WHERE IT CANNOT BE SKIPPED

A gate protects nobody until something runs it without being asked.

Local and CI enforcement are not alternatives. A client-side hook only protects
whoever armed it and can be bypassed; CI protects the consumers regardless but
lets a red trunk exist for the length of a run. Both, so the fast one catches it
before the push and the authoritative one catches it anyway.

A gate reachable only by a human typing its target is not armed. If it is worth
having, it joins an aggregate something already invokes; if it is deliberately
NOT in one, that exclusion is written where the aggregate is defined, with the
reason — because the alternative is a reader assuming coverage the aggregate does
not have.
