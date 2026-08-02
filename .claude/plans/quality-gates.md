# Quality gates — the roadmap, and why STEP 0 is measurement

This plan built the gate battery, and every step in it has landed — see STATUS below.
It is kept because the REASONING is the durable part: every step states what had to be
MEASURED before it could be designed, because `.claude/rules/gate-enforcement.md` §1
forbids the alternative — a threshold guessed and then parked behind a baseline.

**MEASUREMENT FIRST WAS THE LOAD-BEARING DECISION**, and it is the one to copy. Four of
the five design steps would otherwise have been built around a borrowed constant: the
surveyed donor repository's thresholds are calibrated to its corpus rather than derived
from it, and two of them turned out to measure a DIFFERENT QUANTITY than the same-named
metric here (its nesting counts `let`; its complexity counts heads rather than arms). A
borrowed number that happens to share a name is worse than no number, because nothing in
it announces the mismatch.

## STATUS — all six STEPs landed

Every step below is DONE and armed. The prose is kept as the reasoning that produced
each gate, not as a to-do list; `git log` carries what each commit changed.

| step | landed as |
|---|---|
| 0 measure | four measurements, each recorded with its reproduction command below |
| 1 instrument | `m/=>` armed at a kaocha `post-load` seam: 318 of 325 vars, 7 primitive-hinted refused and named |
| 2 the join | `make -f renderer.mk spec-coverage` — enrolled namespaces total, zero tolerated misses |
| 3 function grain | `make -f lint.mk lint-fn-size` — loc / nesting / decisions, seeded at measured maxima |
| 4 dead C externs | `make -f renderer.mk dead-c-externs` — intersection over both link targets, seeded at zero |
| 5 perf | `lint` parallelised (103.8 s -> 48.1 s measured interleaved) + JIT capped in the Clojure canary suites |
| 6 revalidation | three-axis fan-out; findings dispositioned, three false claims of my own retracted |

TWO THINGS THIS PLAN GOT WRONG, both worth keeping because the next plan will be
tempted the same way. It proposed a coverage CEILING for STEP 2 — that would have been
148 enumerable findings wearing a number, which §1 refuses; enrolment is the legitimate
shape. And it assumed instrumentation was a repo-wide project, when arrow specs exist in
ONE tree.

## What is already armed

Each lane carries a mutation-attributed canary suite per `gate-enforcement.md` §2. The
`where` column matters: the `lint` aggregate runs on the pre-push hook and a plain CI
runner, while two lanes need compiled objects or the proto classpath and therefore ride
the renderer battery instead.

| lane | what it bounds | where | seeding |
|---|---|---|---|
| `lint-no-host-paths` | no operator-home path in any checked-in file | lint | clean, 1 waiver |
| `lint-ns-size` | namespace code-LOC and public-var count | lint | measured max; donor numbers as the watchlist |
| `lint-fn-size` | function LOC, nesting depth, decision ARMS | lint | measured max (175/7/71), zero exemptions |
| `lint-docstrings` | every `defn`/`defn-`/`defmacro` in an enrolled root | lint | declared scope, zero parked findings |
| `lint-spec-shape` | no bare `:any`/`:map`/`:string` in an `m/=>` position | lint | total over specs that exist; 5 waivers |
| `lint-spec-presence` | every `defn`/`defn-` in an enrolled namespace carries an `m/=>` | lint | declared scope, zero waivers; judged fraction printed every run |
| `lint-md` | markdown frontmatter, code spans, path citations | lint | clean, 1 waiver |
| `lint-ci` | workflow syntax, over staged AND unstaged files | lint | clean |
| `spec-coverage` | every enrolled `m/=>` spec is EXERCISED and checked | battery | declared scope, 12/12 |
| `dead-c-externs` | no unreachable external symbol in hand-authored C | battery | intersection over both links, 0 |
| `readability-function-size` | C function lines/statements/branches/nesting/vars/params | CI image | measured max, six axes |

Two things about that table are worth carrying forward rather than rediscovering.
**The C lane has no DEGRADED tier and cannot have one** — clang-tidy takes one
threshold set per run — so it meets two of `gate-enforcement.md` §1's three
conditions and the third is a standing, disclosed exception. And **`lint-c-tidy`
has no runnable canary suite** — only a hand-executed proof recorded in its commit
message. That is a gap, not a decision.
The gap that sat beside it is CLOSED: it used to be in neither the `lint`
aggregate nor the hook, so a C size regression landed locally green and reddened
only in CI. `.githooks/pre-push` now calls it separately and docker-gated. It
stays out of the `lint` aggregate deliberately, because `lint` is invoked bare
and folding a container-only lane in would hard-fail every push from a machine
without the image.

## STEP 0 — MEASURED. The numbers below are what the rest of this plan is built on.

All four measurements are done. Each is reproducible by the command recorded with
it, and every one of them changed a design decision downstream — which is the
argument for having measured first rather than borrowed.

### 0a. Clojure per-function LOC, nesting depth, decision count — DONE

Measured by `lint-gate.fnsize`, which is the SAME code the gate runs, so a seeded
ceiling cannot disagree with the gate that enforces it. Over **1483 functions in
175 files** across every gated Clojure root, generated projections excluded, 0
files unparseable:

| metric | max | p99 | p95 | p90 | median |
|---|---|---|---|---|---|
| function code-LOC | **175** | 72 | 37 | 25 | 8 |
| nesting depth | **7** | 5 | 3 | 3 | 1 |
| decision count | **71** | 20 | 9 | 6 | 1 |

Per tree, which is what makes the enrolment question answerable:

| tree | files | fns | `m/=>` specs | max LOC | max decisions |
|---|---|---|---|---|---|
| `tools/devcards` | 83 | 690 | 0 | 175 | 31 |
| `tools/renderer-gen` | 46 | 437 | **325** | 143 | 71 |
| `docs/.protodoc/tools` | 40 | 264 | 0 | 83 | 55 |
| `tools/lint` | 6 | 92 | 0 | 38 | 12 |

Reproduce: `tools/lint/src/lint_gate/fnsize.clj` via the `fn-size` check.

**THE NESTING NUMBER IS NOT COMPARABLE TO THE EARLIER ONE, and the difference is
the head set rather than the tree.** The 9 recorded from the donor survey counts
`do`, `let` and `fn`; this 7 counts branching and iteration only, because a `let`
introduces names and not a condition — nothing about it changes WHETHER the next
line runs. `nesting-heads` in `fnsize.clj` carries the full argument. Consequence:
**the donor's threshold of 5 measures a different quantity and is not borrowed.**

**DECISIONS ARE COUNTED AS ARMS, NOT HEADS**, so a forty-arm `case` scores 40 and
not 1. This tree's largest namespaces are codegen dispatch tables — exactly the
shape a head count scores as trivial — so a head-counting metric would have been
blind to the code it most needed to see. `decisions` in `fnsize.clj` carries it.

Two spot-checks, because a metric wrong in an obvious place is wrong everywhere.
`parse-frontmatter` (`tools/lint/src/lint_gate/md.clj`) scores 7 and its chain is
`if-not → if-let → fn → cond → if → fn → if`, counted by hand. `brief-md`
(`tools/devcards/src/devcards/standard_brief.clj`) scores 175 over rows 312–508:
185 non-blank non-comment lines minus a 10-line docstring, verified independently.
**The docstring subtraction is therefore live**, which is the property that keeps
this metric out of conflict with `lint-docstrings`.

### 0b. Test coverage — SUPERSEDED for the tree that has specs, still owed for two

The premise correction stands: the donor does NOT hold its Clojure to 85%; that is
its Go gate, and its one Clojure cloverage ratchet covers a shipped binary
protogen does not have.

What replaced it is stronger and is recorded under 0c/STEP 2: for
`tools/renderer-gen` the JOIN measures which specced functions actually run and
whether their contracts hold, which line coverage cannot answer. **Line coverage is
still the only available signal for `tools/devcards` and `docs/.protodoc/tools`,
because those two carry zero arrow specs** — so the join degenerates there and
cloverage remains owed for exactly those two trees.

### 0c. The instrumentation blast radius — DONE, and it is a SWITCH not a project

Measured in `tools/renderer-gen`, the only tree with arrow specs.

**325 specs registered across 34 namespaces. 318 instrumented. 7 refused, and they
are named** — malli declines primitive-hinted functions, nuance 5 below:
`uigen.cmd-spec/{varint-le-bytes,byte-len,double->le-bytes,padded-varint}` and
`lvgl-codegen.palette-ladder/{linear-rgb->wcag-y,oklch->linear-rgb,floor-ratio}`.
They are excluded from every denominator rather than silently counted.

**Blast radius: 3 violations, ONE distinct function** —
`lvgl-codegen.palette-ladder/hex->rgb8`. The suite stays at 74 tests / 3328
assertions / 0 failures.

**THAT NUMBER IS ONLY WORTH ANYTHING BECAUSE THE BLINDNESS CONTROL PASSED**, and
the control is the part to keep. "One violation" is exactly what a dead
instrumentation pass also prints, so two things were measured that do not depend on
finding any violation at all:

1. **Coverage by identity** — count specced vars whose value `instrument!`
   actually REPLACED. 318 replaced, 7 untouched, and the 7 are precisely the
   primitive-hinted set. A var left identical is a spec nothing can ever check.
2. **Liveness by forced violation** — hand 8 instrumented functions an argument of
   deliberately wrong type and require the report to fire. **8 of 8 fired.**

Without those, every green here would have been decoration. The probe is
`blind.clj`; it is the shape any future arming must re-run.

### 0d. The C dead-external-linkage set — DONE, and STEP 4 has a subject

Root set DERIVED from `renderer/wasm.mk`, never hand-copied: **36
`-Wl,--export=` names, 36 distinct** — the earlier count is confirmed. 34 are
defined in hand-authored objects; `malloc` and `free` come from the sysroot.

Over the 8 hand-authored objects (`build/release/src/*.o` less the generated
`font_*`), 69 external definitions:

| link target | exported | referenced | candidate dead |
|---|---|---|---|
| `controls.wasm` | 34 | 33 | **2** |
| `reference.wasm` | 34 | 27 | 8 |
| **intersection** | | | **2** |

**THE COMBINATOR IS INTERSECTION, NOT UNION, and this is the half the plan did not
specify.** The reference link shows 8 candidates, but 6 of them are unreferenced
there only because `renderer.o` — which uses them — is not in that link. A symbol
is dead only if it is unreferenced in EVERY link its defining object participates
in. A union over targets reports 6 false positives; a whole-tree merge hides real
ones. Both failures are now measured rather than argued.

The dual-definition hazard is real and **larger than recorded: 8 symbols, not 5**,
are defined in both `renderer.c` and `reference_ui.c`
(`apply_patch_from_proto_raw`, `build_ui_from_proto_raw`, `cmd_spec_decode_probe`,
`proxy_report_sweep`, `renderer_cleanup`, `renderer_proxy_part`,
`renderer_proxy_root`, `update_state_from_proto`).

**THE TWO CANDIDATES ARE NOT DEAD CODE, AND THE DISTINCTION MATTERS.** Both are
CALLED — they are needlessly EXTERNAL:

- `cmd_patch_padded_varint` — used only at `renderer/src/cmd_patch.c:103`, declared
  in `cmd_patch.h`.
- `gesture_ndc_dist` — used only within `renderer/src/gesture.c`, declared in
  `gesture.h`.

So the finding is a LINKAGE finding, not a liveness one, and the honest
consequence is that this analysis found exactly what
`misc-use-internal-linkage` targets. What it adds over clang-tidy is the part
clang-tidy cannot have: clang-tidy is per-TU and cannot know whether another
object uses a symbol, so it needs an export-aware filter AND still cannot answer
the cross-TU question. `llvm-nm` over the whole link set answers it directly.

Reproduce, from `renderer/`, in the pinned toolchain:

```
make -f wasm.mk objects
make -f wasm.mk reference
/opt/wasi-sdk/bin/llvm-nm --defined-only build/release/src/*.o
/opt/wasi-sdk/bin/llvm-nm --undefined-only <every object in the link>
```

Two hazards, both still live. `CFLAGS` carries `-MMD -MP`, so a compile command
reused for a "read-only" dump writes depfiles — the runs above stay inside
`build/`, which is gitignored. And `make -f wasm.mk objects` builds ONLY the
`controls.wasm` set: `reference_ui.o` and the demo objects need `make -f wasm.mk
reference`, so an analysis that skips it silently judges one target and reports as
if it judged both.

### 0b. Test coverage per tree — and the premise needs correcting first

**The donor does NOT hold its Clojure to 85%.** It applies cloverage ≥85% to ONE
tree, its operator-shipped binary; its other Clojure coverage ratchet was RETIRED;
and the repo-wide 85% is its **Go** gate. Its own comment on the Clojure one warns
against tightening pre-emptively "on future less-testable code where 100% unit
coverage isn't realistic".

protogen ships no Clojure binary — `tools/devcards`, `tools/renderer-gen` and
`docs/.protodoc/tools` are all build and gate tooling. So "which tree" is a real
scoping question and the answer is not "all of them".

Measure per tree, with cloverage added as a `deps.edn` alias (a new dependency, and
the only one this plan needs):

- `docs/.protodoc/tools` — the doc generator. Its suite is already large.
- `tools/renderer-gen` — the LVGL vocabulary and proto emitters.
- `tools/devcards` — the corpus runner.

**Expect these to score well already, and that is the point rather than a
disappointment.** The operator's framing is the right one: this tree carries a great
deal of E2E synthetic data, so the doc-gen and the LVGL/proto code are substantially
exercised. The coverage number is not the deliverable — the deliverable is the JOIN
described in STEP 1, which uses coverage to find which exercised code paths actually
evaluate a Malli contract. Coverage alone says a line ran; it does not say a
contract was checked.

### 0c. The instrumentation blast radius

Before arming anything: how many of the `m/=>` specs go RED when instrumentation is
switched on? `.claude/rules/malli-schemas.md` predicts the shape and it runs against
intuition — the LOOSE specs keep passing, because `:any` accepts everything, so
arming cannot be the mechanism that cleans them up. What goes red is specs that
MIS-DESCRIBE their function, which is the likely state of any spec nothing has ever
checked.

Measure it as a throw-away probe, not as a landed change: arm instrumentation in a
scratch runner, run each tree's suite, count and classify the failures. That count
decides whether STEP 1 is a switch or a project.

### 0d. The C dead-external-linkage set

One command, and it is the cheapest high-value measurement in this plan. From
`renderer/`, in the pinned toolchain:

```
make -f wasm.mk objects
/opt/wasi-sdk/bin/llvm-nm --defined-only build/release/src/*.o
/opt/wasi-sdk/bin/llvm-nm --undefined-only build/release/src/*.o
```

The candidate set is `defined-extern − exported − undefined-in-another-object`,
where `exported` is the 36 `-Wl,--export=` names in `renderer/wasm.mk`. If it is
empty, STEP 4 is DECLINED and the honest recommendation collapses to re-enabling
`misc-use-internal-linkage` behind an export-aware filter. If it is non-empty, STEP
4 has a subject.

**Two hazards to respect, both measured elsewhere and neither obvious.** `CFLAGS`
carries `-MMD -MP`, so reusing a compile-database command verbatim for a
"read-only" dump WRITES depfiles into the tree. And the analysis must run ONCE PER
LINK TARGET: five externs are defined in BOTH `renderer/src/renderer.c` and
`renderer/src/reference_ui.c`, which are never linked together, so a whole-tree
union collapses them into one node and hides findings.

## STEP 1 — Arm `m/=>` instrumentation for the test runs

The point is not type safety at runtime; it is that a refactor which changes what a
function accepts becomes a RED TEST instead of a silent behaviour change. That is
what makes every later step safer, which is why it comes first.

**SCOPE, MEASURED, AND IT IS ONE TREE.** Arrow specs exist ONLY in
`tools/renderer-gen` (325 of them). `docs/.protodoc/tools` uses malli
IMPERATIVELY — 35 `m/validate` call sites and no `m/=>` — so instrumentation there
would arm nothing. `tools/devcards` has malli on its classpath and zero arrow
specs; its `m/` alias is an unrelated namespace entirely, which is why a grep for
`m/` overstates its malli usage. So this step arms ONE tree, and a plan that
implied a repo-wide project was wrong about the population.

**The one function to fix before arming:** `lvgl-codegen.palette-ladder/hex->rgb8`
is the sole spec that fails under instrumentation. Arming with a throwing report
before that is fixed turns the suite red on arrival.

`.claude/rules/malli-schemas.md` is the governing rule and already states the two
hard parts: arming is ONE `instrument!` at a seam the trees already pass through,
never a call per entry point; and the moment it is armed the spec population is a
GATE and owes `gate-enforcement.md` §2 in full — a constructed known-bad input it
rejects, a FAIL distinguishable from an ERROR, attribution to the clause under test,
and proof the mutation landed.

**Five nuances, every one of them a way this silently does nothing.** All are
documented first-hand in the donor's runner and its tests; none is guesswork.

1. **A DELTA WATCHER IS MANDATORY.** `collect!` + `instrument!` covers only the
   namespaces loaded at that instant. Anything `require`d later is silently
   UNINSTRUMENTED. The donor pins this with a test whose own docstring notes that a
   naturally-throwing body would false-green an uninstrumented var — so the canary
   for this must assert on a var that would otherwise PASS.
2. **`malli.dev/start!` is quadratic** in the number of registered specs. Measured
   in a sibling at ~765 ms and 85% of a hook's load time. Do not put it on any path
   that runs per-invocation; the test seam is the right home precisely because it
   runs once.
3. **`:report` fires only on FAILURE, never on success.** So instrumentation alone
   cannot measure coverage — that needs a hit-recording wrapper ON TOP of it, which
   is what makes STEP 2's join possible at all.
4. **`with-redefs` BYPASSES instrumentation.** A mocked function is not covered and
   its contract is not checked. Covering an IO function therefore needs a real call
   with its EFFECTS stubbed, not the function itself mocked. This will change how
   some existing tests must be written.
5. **Primitive-hinted functions cannot be instrumented** — malli refuses, and a
   plain variadic wrapper breaks their `IFn$OL` invocation path. They must be
   reported SEPARATELY and excluded from the denominator, never silently counted.
   MEASURED: 7 of them here, named under 0c. And the cost of that exclusion is not
   theoretical — `uigen.cmd-spec/padded-varint` is one of the 7 AND is half of a
   cross-language wire mirror, so its spec was decorative and its exclusion hid it
   from the join at the same time. It took a test, not a spec
   (`test/uigen/cmd_spec_test.clj`).
6. **ARMING SILENTLY DISARMS EVERY NEGATIVE-PATH TEST**, and this one was found by
   arming rather than predicted. A function that validates its own input and throws
   cannot have a tight input schema AND be negative-tested through its var:
   instrumentation refuses the argument first, so the guard never runs — and
   malli's refusal is itself an `ExceptionInfo`, so `(is (thrown? ExceptionInfo …))`
   passes either way, INCLUDING with the guard deleted from the function.

   This is the whole of 0c's blast radius. The single reported violation,
   `palette-ladder/hex->rgb8`, was not a defect and not a mis-described spec —
   instrumentation had found its NEGATIVE TEST. The repair is owed BEFORE arming
   and belongs in the assertion: match the message with `thrown-with-msg?` so the
   function's guard is distinguishable from malli's wrapper. Then sweep every
   remaining bare `thrown?` on a self-validating function, because each one is
   evidence about neither layer.

## STEP 2 — The coverage floor, as a JOIN rather than a percentage

This is the operator's framing and it is sharper than a line-coverage number.

With STEP 1 armed and a hit-recorder in place, the question a push gate can answer
is: **for each function carrying an `m/=>`, was it CALLED, and did its contract
HOLD?** That partitions the corpus three ways:

- **exercised and contract-checked** — genuinely covered.
- **exercised but no contract** — a spec so loose it asserts nothing. Line coverage
  counts this as covered; it is not.
- **not exercised** — the real test gap, and the only tier that needs new tests.

### THE PARTITION IS MEASURED, and WHICH ENTRY POINT YOU RUN CHANGES THE ANSWER 3x

Denominator 318 instrumentable specs. Hits recorded by wrapping each var AFTER
`instrument!`, so a hit means "called through the instrumented path":

| legs run | exercised + checked | arity-only | no-input | NOT exercised |
|---|---|---|---|---|
| unit suite (`clojure -M:test`) | 50 | 1 | 0 | **267** |
| + `:fixtures` | 128 | 1 | 2 | 187 |
| + `:codegen` | 133 | 1 | 2 | 182 |
| + `:morph-fixtures` | **165** | 1 | 4 | **148** |

**THE UNIT SUITE ALONE IS A TRAP, and this is the single most useful thing STEP 0
produced.** Measured against `clojure -M:test` the gap reads 267 and would send
someone writing 267 tests. The E2E synthetic-data generators — which `renderer.mk`
drives and the unit suite never touches — MORE THAN TRIPLE the exercised set, from
50 to 165. **The true gap is 148, and the 119 specs in between are already
exercised by a path a naive coverage run does not execute.** So the gate must drive
the generator legs too, or it measures the wrong number by a factor of three.

`arity-only` is **1**, not a tier: the loose-spec problem is already gone, because
`lint-spec-shape` cleaned it. Contract violations stay at 3/1 across every leg, so
the E2E legs introduce no failure the unit suite misses.

**Two limits on that 148, both in the safe direction.** A function captured into a
data structure at load time is called through the captured value, so neither the
wrapper nor instrumentation sees it — exercise is UNDER-counted, meaning the true
gap is at most 148 and possibly smaller. And `with-redefs` bypasses instrumentation
(nuance 4), so a mocked function counts as neither exercised nor checked.

Only after that partition does a NUMBER make sense, scoped per tree with the
population named. A bare repo-wide 85% would be a borrowed constant of exactly the
kind STEP 0 exists to avoid — and for the two trees with no specs at all, this join
cannot be computed and line coverage is the only signal available.

## STEP 3 — The three function-grain Clojure gates

`function-length`, `nesting`, `complexity`. Design is settled; only the numbers are
missing, and STEP 0a supplies them.

Shape, for all three, matching what `lint-ns-size` already does:

- **BLOCK seeded at this tree's measured maximum** — green on arrival, ZERO
  exemptions, down-only. A ceiling still needing waivers has been guessed, not
  seeded.
- **The donor's number as the reported DEGRADED watchlist** — non-blocking, printed
  every run, so the distance to that bar stays visible instead of being quietly
  redefined as fine. `lint-ns-size` already does this with the donor's 400/25.
- **Provenance beside every number**, or a later reader cannot tell a seeded ceiling
  from an arbitrary one and will raise it.

Cost is low and the layout is built for it: one namespace under
`tools/lint/src/lint_gate/`, one entry in `core.clj`'s `checks` map, one config key
in `tools/lint/gates.edn`, two lines in `lint.mk`, and cases in
`tools/lint/test/lint_gate_checks_test.sh` using the existing fixture and mutation
helpers.

**One substrate decision to settle first.** `function-length` needs only clj-kondo's
analysis, which the gate already fetches. `nesting` and `complexity` need the FORM
TREE. `clojure.core/read-string` in a loop serves for `.clj` — `lint-gate.specs`
already does exactly this — but a reader dependency would break the zero-dependency
property that makes the `:lint-gate` alias cheap. Decide before writing code; it is
the whole cost of those two.

## STEP 4 — The C dead-export ratchet, if STEP 0d gives it a subject

Root at the linker's export list, DERIVED not hand-copied. The donor's analyser
roots at `main`, which degenerates completely here: this renderer is
`-mexec-model=reactor` and has no `main`, so its entry set would be empty and every
reachable symbol would fall into the advisory tier.

The one insight worth taking from the donor's design: **the REFERENCE, not the CALL,
is the liveness carrier.** A bare `f` whose address is taken —
`lv_obj_add_event_cb(…, cb)`, `funcs.decode = cb` — is a reference outside any call.
Keying edges on calls "reported every callback in the tree as dead", and this tree
has roughly fifty such registration sites. A third sibling holds the PRE-FIX,
call-keyed version of the same analyser; porting from the wrong one would produce
the detector maximally wrong for this codebase.

Note what already covers half of this: `-Wunused-function` under `-Werror` makes an
unused `static` a hard BUILD failure, and iterating build-delete-build completes the
cascade at fixpoint on every class EXCEPT cycles. The graph's genuine advantage is
one pass instead of N, plus cycles. State it that narrowly.

## STEP 5 — Performance, after correctness

Only once the gates are settled, because a faster gate that checks less is a
regression. `.claude/skills/perf-investigation/SKILL.md` is the playbook and carries
the measurement discipline — interleaved reruns, relative shares, the JVM tracers
actually present in the pinned container.

Known cost to attack, measured this session: **the markdown canary suite went from
2.0 s to 58 s** when it was ported off python, because 66 cases now each pay a JVM
start. `-XX:TieredStopAtLevel=1` recovers most of the CPU; AOT would be the real
fix. That cost is paid by `lint`, so it is felt on every push.

Beyond it: profile the gate lanes and the renderer battery with the container's own
tracers, find the hot paths, and **run concurrently what can run concurrently** —
the `lint` aggregate is currently a serial prerequisite chain, and several lanes are
independent. The trap to avoid is the one the perf skill names: a naive speedup that
makes a gate silently check less.

## STEP 6 — Revalidate the docs, skills and rules, and DE-DUPLICATE across the fleet

Last, and agentic — a fan-out that judges every `.md`, every skill and every
docstring against three axes:

1. **Anthropic's published guidance** for CLAUDE.md, skills and subagents — the
   authority on the harness, and the one axis this repo has never checked itself
   against.
2. **Glob robustness.** A path-scoped rule earns its keep only if it LOADS when
   someone digs into the code it governs. Verify each `paths:` matches the files a
   reader would actually be editing, and that a rule which should be unscoped is not
   accidentally scoped. `lint-md` already checks frontmatter SHAPE and that a glob
   matches something; it cannot check that the glob matches the RIGHT something.
3. **DE-DUPLICATION ACROSS THE FLEET, which is the reason this step exists.**
   protogen is a submodule of the larger systems, so a generic rule living in both
   places is two copies that drift — and a rule that CONFLICTS is worse, because
   each reads correctly alone. The test: does this rule say something SPECIFIC to
   protogen (its wire contract, its renderer, its gates), or something generic about
   Clojure/C/review that the superproject also says? Generic content belongs in one
   home with the other pointing at it; `.claude/rules/widget-consumer-duty.md` §12
   already states this law for the widget surface and it generalises.

Constraint on this step: agents resolve skills and rules from the PROJECT ROOT, not
from a submodule mount, so a rule verified here is NOT thereby loaded at a
consumer's mount point. Any conclusion about fleet-wide loading must be checked from
a consumer, not from this repo.

## Open defects, carried forward

- **`lint-c-tidy` has no canary suite.** The C size check's proof exists only as a
  hand-executed sequence in its commit message. `gate-enforcement.md` §2 wants it in
  a runnable suite; the mutation shape is known (set one axis to 1 and the rest to
  9999, require a FAIL naming that axis, plus a control proving the neighbours
  stayed quiet). The wiring half of this defect is closed — the pre-push hook now
  calls the lane, docker-gated — which makes the missing canary the sharper of the
  two rather than the lesser: a lane that now runs on every push is a lane whose
  ability to fail nobody has demonstrated.
- **The C provenance comment in `renderer/.clang-tidy` attributes all six measured
  maxima to one function, and at least one attribution is wrong** — the named
  function spans far fewer lines than the recorded figure. The THRESHOLDS are
  correct and were measured; only the attribution needs re-deriving. It matters
  because provenance is what stops a later author raising a number.
- **`readability-function-cognitive-complexity` is declined in that same file with a
  reason that now sits beside an enabled six-axis size check.** Either the decline
  reasoning is rewritten or the check is seeded like the others. It is not
  redundant: it weights NESTED branching super-linearly, which a max-depth and a
  flat branch count both miss.
- **`docs/.protodoc/scripts/` is still held out of every Clojure lane**, on the
  record at the `LINT_CLJ_PATHS` declaration, retiring when those scripts carry `ns`
  forms. (`tools/renderer-gen/dev` WAS also ungated and no longer is — its three
  probes gained `ns` forms and joined the gated path list, which is the same fix those
  scripts need.)
- **Arrow-spec PRESENCE is unenforced** — measured at 1067 of 1391 functions missing
  a spec. Declined as a project, not a gate; STEP 1 does not change that, because
  instrumenting a spec that does not exist checks nothing.
- **~4,000 lines of pre-existing python remain**, none of it a gate except
  `tools/wire_contract_check.py`, which gates the wire contract from three workflows
  and the pre-push hook. Porting it needs the descriptor pipeline and is its own
  change.
