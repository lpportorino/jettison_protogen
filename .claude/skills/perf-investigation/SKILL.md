---
name: perf-investigation
description: Find and fix performance problems in protogen builds, gates and tooling. Use when a lane/target/suite is slow, when asked to speed something up or parallelise it, or before/after any change intended to make something faster. Covers the busy-box measurement discipline (interleaved reruns, relative shares), the JVM tracers actually present in the pinned container, and the gate-specific traps that make a naive speedup silently wrong.
---

# Perf investigation — measure relative, interleave, then fix

Two things make performance work here different from the generic advice, and
both are load-bearing:

1. **THIS BOX IS USUALLY BUSY.** Background agents, containers and other
   sessions share it. An absolute second count taken once is close to
   meaningless — measured in-tree, one lane ran 48.6s / 60.9s / 70.1s across
   three interleaved rounds of the SAME command while loadavg climbed 4.52 ->
   17.45. A 30% swing on an unchanged command is larger than most optimisations
   you would be trying to detect.
2. **MOST THINGS HERE ARE GATES.** A gate that gets faster by judging less is a
   catastrophic regression wearing a green badge. Speed work on a gate carries
   the same burden of proof as a correctness change.

## The measurement discipline — non-negotiable

**USE `tools/perf/bench.sh`. Do not hand-roll `time`.**

    tools/uber.sh 'tools/perf/bench.sh -n 3 -l <label> -- "<cmd A>" "<cmd B>" ...'

It bakes in the three things that are otherwise forgotten:

- **INTERLEAVE, never group.** It runs round-robin (A B C, A B C, A B C), not
  (A A A, B B B, C C C). This is the whole point: on a box whose load drifts
  during the run, grouped repeats CANNOT distinguish "B is slower" from "the box
  got busier while B was running". Round-robin taxes every variant roughly
  equally, so the comparison survives the drift. **Total box load affecting all
  tasks equally is the goal, not a nuisance.**
- **REPORT RELATIVE.** The headline is each variant SHARE of the round total.
  Shares are stable when absolutes are not — measured: absolute medians moved
  ~30% between two sessions while the ranking and the ~50% share of the top lane
  did not move at all. Quote shares and ranks. Quote absolutes only as context,
  and only with the loadavg they were taken under.
- **SHOW THE SPREAD.** Median (not mean — one descheduled run must not move the
  result), plus min..max, plus a `[noisy]` marker when the spread exceeds 30% of
  the median. **Two variants whose spreads OVERLAP are not distinguished by that
  run.** Raise `-n`, or quiesce the box, before claiming a difference.

`-n 3` is the floor. Use `-n 5` when spreads overlap or when the claimed win is
under ~20%.

**Logs are gitignored.** Every run writes `.protogen/perf/<stamp>-<label>.tsv`
(raw rows) and `.summary.md` (the reduction), under the repo-wide `.protogen/`
ignore. That directory is invisible to a fresh clone and to all consumers — so a
perf FACT that should outlive the session goes in a commit message or a rule,
per `.claude/rules/` and the session-winddown skill. The TSV is for the next
comparison; it is not a durable record.

Always capture a BEFORE run and an AFTER run with the same label and `-n`, and
put both summary paths in the commit message. A speedup claim with no before/
after pair in the log directory is an assertion, not a measurement.

## What tracers actually exist here

Verified present in the pinned container (`Dockerfile.base`, GraalVM CE 25):

| tool | use |
|---|---|
| `jcmd` | the entry point — `jcmd <pid> help` lists what a live JVM will answer |
| `jcmd <pid> Thread.print` / `jstack` | where a hung or slow JVM actually is |
| `jcmd <pid> JFR.start/dump/stop`, `jfr` | sampling profile of a JVM lane |
| `jmap` | heap histogram when allocation is the suspect |
| `vmstat`, `nproc`, `/proc/loadavg` | is the BOX the problem, or the code |

**Verified ABSENT — do not write instructions around them:** `perf`, `strace`,
`ltrace`, `valgrind`, `bpftrace`, `hyperfine`, `pidstat`, `mpstat`, `iostat`.

Consequences worth knowing before you plan an investigation:

- **No `perf`, so native/wasm lanes get no sampling profile.** For those, bisect
  by structure (time the phases, count the process spawns) rather than by
  profiler. Counting invocations is often enough — see the worked example below.
- **No `strace`, so syscall/startup cost is inferred, not observed.** The honest
  move is to state it as inference and support it arithmetically (N spawns x
  measured per-spawn floor), not to assert a syscall breakdown you cannot see.
- **No `hyperfine`** — that is what `tools/perf/bench.sh` replaces.

### JFR on a JVM lane, concretely

    # start the lane, find it, profile it
    jcmd -l                                   # list JVMs
    jcmd <pid> JFR.start name=p settings=profile
    # ... let the slow phase run ...
    jcmd <pid> JFR.dump name=p filename=/tmp/p.jfr
    jfr summary /tmp/p.jfr                    # what got recorded
    jfr print --events ExecutionSample /tmp/p.jfr | head -100

**Check the lane is JVM-bound before reaching for JFR.** A refuted hypothesis
from this repo, recorded so it is not re-run: eight battery lanes shell out to
`clojure`, so repeated JVM startup looked like the obvious cost. Measured, a
bare `clojure -M -e nil` in this image is ~0s. Startup was NOT the cost and no
lane should be restructured around it. **Measure before profiling; profile
before optimising.**

## The traps that make a speedup wrong

### A parallelised gate that reports through shell state is VACUOUSLY GREEN

The single sharpest trap in this repo. Verdicts written as shell counters:

    pass=0; fail=0
    run_case() { … fail=$((fail + 1)) … }
    [ "$fail" -eq 0 ]           # the entire verdict

Put `run_case` behind `xargs -P` or a trailing `&` and every increment happens
in a SUBSHELL. The parent never sees it, `fail` stays 0, the test succeeds, and
the lane reports `0 matched, 0 diverged (of 0)` while exiting GREEN having
judged nothing. It does not fail loudly. It passes silently, which is the one
failure class this repo refuses everywhere.

**Before parallelising any loop, find where the verdict comes from.** If it is a
variable mutated inside the loop body, restructure FIRST: each iteration writes a
per-case result artifact, and the count is REDUCED from those artifacts after the
loop joins. Then add a canary that deletes one result file and asserts the lane
goes red for MISSING RESULT — otherwise "judged nothing" and "judged everything"
stay indistinguishable, which is exactly the bug you were trying to remove.

### `make -j` needs DECLARED dependencies, not lucky list order

A target list like `check-renderer: a b c d` runs left-to-right under serial
make, so an undeclared dependency between `b` and `d` is invisible. `-j` discards
that ordering and the race appears — nondeterministically, which is the worst
way for a gate to fail.

Before adding `-j`: for each lane, list what it WRITES and what it READS, and
confirm every read-after-write pair is a declared prerequisite. Watch
particularly for a generated file installed with `cp` (truncate-then-write, not
atomic) that other lanes read. Prefer `mv` within one filesystem for installs —
a correctness improvement independent of parallelism.

### Parallelising a shell loop — four traps, all hit while writing this skill

Every one of these was met in a single afternoon parallelising one lane. They
are cheap to avoid and expensive to debug.

**A regex over call sites rewrites the DISPATCHER OWN call.** Introducing
`dispatch_row` and then running `s/^\s*run_row /dispatch_row /` over the file
also rewrites the `run_row "$@" &` INSIDE `dispatch_row`, making it call itself.
That is a literal fork bomb: it died with `fork: Cannot allocate memory` before
a single case rendered, on a box with 31 GB free. Diagnosing it as memory
pressure would have been wrong. After any mechanical call-site rewrite, assert
the dispatcher does not reference itself:

    awk '/^dispatch_row\(\)/,/^}/' <file> | grep -c dispatch_row   # must be 1

**A polling slot-check forks, and it spins.** The obvious form,
`while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do …; done`, forks a subshell AND
a `wc` on every poll, hot, while waiting. `wait -n` blocks with zero forks. On a
contended box the polling form is self-defeating — it adds load to the thing it
is waiting for.

**SCHEDULING state may be shell state; VERDICT state may not.** The slot counter
is safe because the dispatcher runs in the PARENT shell. The pass/fail counter
was not, because the work runs in a subshell. Both are "a variable in a loop" and
only one is a bug — the distinction is which shell mutates it, and it is worth
stating in the code so the next reader does not "fix" the safe one or trust the
unsafe one.

**Parallel output reorders, and a gate log must stay diffable.** Have each worker
write its lines to a per-case file and replay them in sorted order after the
join. Then PROVE it: run serial and parallel and diff the case lines. Measured
here, the two are byte-identical across 92 cases — which is the claim you want to
be able to make, and cannot make from a green exit code alone.

### Report the speedup WITH the load it was measured under

Measured here: the parallelised lane went 99.3s -> 69.5s median, a 1.43x win with
non-overlapping spreads (95.0..112.0 vs 67.6..70.2), so the difference is real.
But loadavg during that run was 101 -> 114 on 12 CPUs. **There were no free
cores.** The honest statement is "1.43x at loadavg ~110", not "1.43x", because
the mechanism (184 process spawns going concurrent) has far more headroom on an
idle CI runner and the same change will measure very differently there.

A speedup number with no load figure beside it is not reproducible and should not
go in a commit message alone. `bench.sh` records loadavg before and after for
exactly this reason — quote it.

### Speeding up by measuring less

Sampling a corpus, capping iterations, dropping a theme or a breakpoint all make
a lane faster and make its green mean less. If coverage changes, that is a
CONTRACT change, not an optimisation — it needs the same review, and
`docs/UI-QUALITY-CONTRACTS.md` §0 forbids a verdict implying more than its
measurement can see. If you do bound coverage, `log()` what was dropped; silent
truncation reads as "covered everything".

## The routine

1. **Measure the whole first**, with `bench.sh -n 3`, all candidate lanes in one
   interleaved run. Rank by SHARE. Do not optimise anything below the top two or
   three — you cannot detect the win.
2. **Find the hot path inside the top lane.** Prefer counting (invocations,
   spawns, cases) over profiling; reach for JFR when the lane is JVM-bound and
   counting was not enough.
3. **State the mechanism before the fix.** "184 process spawns at ~0.3s each,
   dominated by startup" is a mechanism. "It is slow" is not, and a fix aimed at
   a guess usually moves nothing measurable.
4. **Fix correctness blockers BEFORE speed.** Undeclared deps and shell-counter
   verdicts are correctness bugs that happen to block parallelism. Landing them
   first means the speedup does not have to be trusted.
5. **Re-measure with the same label and `-n`.** Compare SHARES and medians;
   check the spreads still separate. Put both summary paths in the commit.
6. **Re-run the gate for its VERDICT, not just its clock.** A faster lane must
   still be red on the thing it was built to catch — run its canary, or mutate
   the defect and watch it fail. Never accept a green that only got quicker.

## Recording what you find

Perf findings rot fast and `.protogen/` is gitignored. Route them:

- a mechanism another session would re-derive expensively -> the commit message
- a rule about HOW to measure or what not to do -> `.claude/rules/` or this skill
- raw numbers, before/after pairs -> `.protogen/perf/`, and say in any handoff
  that they are local-only
- a refuted hypothesis -> write it down explicitly. "JVM startup is not the cost
  here" saved a redesign; an unrecorded refutation gets re-tried every session.
