# Descriptive interleaved benchmark

Run commands from the desired working directory:

```sh
tools/perf/bench.sh -n 3 -l example -- 'command A' 'command B'
```

Requires Bash, Python 3 with `time.monotonic_ns`, awk, GNU coreutils and a Linux
load context (`/proc/loadavg` is optional). Commands execute sequentially in fixed
round-robin order. No warmup, timeout, isolation, randomization or noise
subtraction is supplied. The caller owns workload equivalence and command
completion, including avoiding detached background work. This is an exploratory
measurement runner, not a statistical qualification gate.

## Artifacts and timing boundary

Every invocation creates unique ignored files under `.protogen/perf/`:

- `.tsv`: versioned raw observations and CPU/load/clock context;
- `.runs/round-N-vM.log`: combined stdout/stderr for each command;
- `.summary.md`: descriptive reduction, or an INVALID explanation.

The `.tsv` header names its format version (`protogen bench v2`), whose columns
are `round`, `variant_idx`, `variant`, `elapsed_ms`, `exit`, `elapsed_ns`. Do not
combine files of different versions. Command strings cannot contain tabs/newlines.
Paths and command output may contain sensitive data; inspect before publishing.

Python's monotonic timer starts immediately before launching `bash -c` and ends
when that shell exits. It includes shell startup and scheduling/wait time,
excludes Python launcher startup, and does not wait for detached descendants.
The clock implementation and advertised resolution are recorded; that resolution
is not an end-to-end error bound. Raw nanoseconds are preserved; the TSV's
`elapsed_ms` column is their truncation. Nonpositive intervals invalidate the
reduction. Every positive interval is kept, and every one includes the shell's
own startup — eight runs of `python3 tools/perf/measure.py /dev/null true` in
the pinned container measured 0.56 to 1.10 ms on a shared host — so batch short
workloads inside each variant until the command, not the launcher, dominates
the observation.

Nonzero command status invalidates the entire comparison, but later commands
still run so their evidence is retained. Missing/malformed observations also
invalidate reduction. The runner exits nonzero, never turning a command failure
into a successful performance comparison.

Infrastructure failures end the run nonzero, but not all of them leave an
INVALID summary:

- before the first observation — `mkdir` of the output paths, `mktemp`, or the
  header's `python3` clock probe — the script stops under `set -euo pipefail`
  with that command's own status (python3 missing: 127). No summary is written,
  and the `.tsv` may already exist holding a partial header;
- a measurement helper that fails or prints a malformed observation writes an
  INVALID summary and exits 1; the observations recorded before it remain;
- a reducer that cannot run at all (no `awk`) exits 1 and leaves an EMPTY
  summary file, with every observation still in the `.tsv`.

## Reduction

For each round `r`, compute `share(i,r)=100*t(i,r)/sum_i(t(i,r))` and
`ratio(i,r)=t(i,r)/t(0,r)` from raw nanosecond observations. Report their medians
independently across rounds; shares and ratios use awk floating point and are
displayed rounded to three decimals, not as exact rational encodings. The
median/min/max elapsed milliseconds are exact: they are computed digit by digit
from the raw nanosecond strings, at any length the TSV holds, and printed with
every nonzero fractional digit (an even-round median can end in half a
nanosecond). Raw integer nanosecond strings remain intact.
Median shares need not add to 100%. A paired ratio above one means slower than
variant 0 in the observed pair. Identical commands, output identity and workload
identity are not verified by this utility.

These are descriptive observations. Fixed order, cache, thermal and unequal
contention effects remain. Overlapping ranges alone do not prove equivalence;
disjoint ranges alone do not prove causality or significance. Loadavg is context,
never a divisor used to manufacture a noise-free duration. These observations
are fixed-order and carry no A/A control pairs, so they cannot be relabelled as
a paired-performance companion (`tools/gate-graph/docs/paired-performance.md`);
a paired comparison needs its own predeclared protocol.

## Verification and manual mutation assessment

Inside the canonical development container:

```sh
make -f lint.mk perf-bench-test
shellcheck tools/perf/bench.sh
python3 tools/perf/test_mutations.py
```

`perf-bench-test` is a lane of the `lint` aggregate the push hook runs, and a
step of its own in the lint CI workflow, whose path filter includes
`tools/perf/**`. Ruff judges this benchmark's helper and its two test drivers
(the files `tools/lint/python_check.sh` enrolls — not the dump-buffer analysis
scripts beside them), and only the renderer workflow runs Ruff in CI; its
filter names `tools/perf/**` too. There is no result cache; each selected
invocation executes the current tests and sources.

The subprocess tests drive the real runner, reducer and bash commands. A test-only
`sitecustomize.py` supplies exact monotonic clock values; production has no clock
injection option. Each test's name states the contract it pins, so read the
suite rather than a list here. The tests do not measure production timing
overhead or prove noise robustness.

The manual campaign runs initial/final baselines and the faults listed in
`FAULTS` in `test_mutations.py`, each in a disposable copy, refusing any fault
whose replacement did not land. Each fault names the assertion it must fail and
a neighbouring control, usually one exercising a different guard, that must
pass. It writes exact replacements, hashes and attributed logs to ignored
`.protogen/perf/mutations/`. Errors, timeouts and unresolved faults fail the
campaign; none count as kills. This bounded campaign is not part of ordinary
push gates.
