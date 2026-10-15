---
name: perf-investigation
description: Find and fix performance problems in protogen builds, gates and tooling. Use when a lane/target/suite is slow, when asked to speed something up or parallelise it, or before/after any change intended to make something faster. Covers the busy-box measurement discipline (interleaved reruns reported with their load), the JVM tracers actually present in the pinned container, and the gate-specific traps that make a naive speedup silently wrong.
---

# Perf investigation — interleave, report the load, then fix

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

The runner retains fixed-order round-robin observations (A B C, A B C),
command stdout/stderr, load context and a descriptive reduction. This can make
slow shared drift easier to inspect than grouped repeats, but it does not
subtract contention or remove order, cache, thermal or workload-specific effects.

- **KEEP THE RAW TIMES.** The TSV header names its format version and units;
  do not combine files of different versions. Python3 `time.monotonic_ns`
  measures bash startup, the command and waiting for its exit; Python launcher
  startup is excluded. Raw `elapsed_ns` and clock implementation/resolution are
  retained. Nonpositive intervals invalidate the reduction. Every interval
  carries bash startup, so batch short work inside the command until it
  dominates. The clock does not remove scheduling delay.
- **PAIR WITHIN ROUNDS.** Ratios use raw nanoseconds, not truncated milliseconds.
  Each round supplies each variant's fraction of total
  elapsed time and its elapsed-time ratio to variant 0. The report independently
  takes the median of these quantities across rounds, and also reports absolute
  median/min/max. Median shares need not sum to 100%. A ratio above 1 means
  slower than variant 0. Neither quantity is a noise-free duration or a stable
  ranking, and loadavg is context, never a normalization divisor.
- **REFUSE INVALID COMPARISONS.** Any nonzero command exit, nonpositive interval,
  missing or malformed observation suppresses the comparative reduction and
  exits nonzero. Raw rows and command output remain for diagnosis. A faster
  failed command is not an improvement.
- **REPORT LIMITS.** Min..max ranges describe the observed sample; they are not
  confidence intervals. Overlap alone does not prove equivalence or lack of a
  difference, and disjoint ranges alone do not prove causality or significance.
  Fixed AB order is still a confounder. No warmup or host isolation is supplied.

Use at least three rounds for exploration; choose further repetitions and a
counterbalanced or randomized experiment when the decision requires uncertainty
estimation. Do not treat an arbitrary round count as statistical qualification.

**Logs are gitignored.** Unique filenames preserve concurrent/repeated same-label
runs in `.protogen/perf/`: raw `.tsv`, `.summary.md`, and `.runs/*.log` command
output. They are invisible to fresh clones. Archive the exact artifacts and
source/tool fingerprints when a performance claim must outlive the session.

Always capture BEFORE and AFTER under the same declared workload and protocol.
Prefer both versions as matched variants within one experiment when possible;
separate sessions do not establish same-round pairing. State the load, order,
coverage and timing boundaries with a claim. Retain both artifact paths in the
commit message; the paths alone are not proof of a speedup.

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

**Check the lane is JVM-bound before reaching for JFR.** Measure the actual
invocation — `bench.sh` over the lane beside `clojure -M -e nil` in the same
run — before attributing a slow lane to JVM startup or excluding startup as a
cause. **Measure before profiling; profile before optimising.**

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

Measured here: the parallelised lane's median went 99.3s -> 69.5s in one run,
with non-overlapping observed ranges (95.0..112.0 vs 67.6..70.2) — a description
of that run, not a proof of cause. Loadavg during it was 101 -> 114 on 12 CPUs.
**There were no free cores.** The honest statement is "99.3s -> 69.5s at loadavg
~110", not a bare ratio, because the mechanism (184 process spawns going
concurrent) has far more headroom on an idle CI runner and the same change will
measure very differently there.

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
   interleaved run. Use raw durations and descriptive shares to identify
   candidate costs; confirm them with workload-specific evidence.
2. **Find the hot path inside the top lane.** Prefer counting (invocations,
   spawns, cases) over profiling; reach for JFR when the lane is JVM-bound and
   counting was not enough.
3. **State the mechanism before the fix.** "184 process spawns at ~0.3s each,
   dominated by startup" is a mechanism. "It is slow" is not, and a fix aimed at
   a guess usually moves nothing measurable.
4. **Fix correctness blockers BEFORE speed.** Undeclared deps and shell-counter
   verdicts are correctness bugs that happen to block parallelism. Landing them
   first means the speedup does not have to be trusted.
5. **Re-measure under the same declared protocol.** Compare paired ratios,
   absolute times and their spread without treating them as significance tests.
   Put both summary paths and limitations in the commit.
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
