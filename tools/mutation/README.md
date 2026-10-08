# Mutation campaigns over shell, Python, browser and Clojure changes

`.claude/rules/mutation-testing.md` holds an epic incomplete while any eligible
mutant survives. `campaign.py` runs a declared campaign; a faults file names its
scope, its suites and every fault.

```sh
python3 tools/mutation/campaign.py tools/mutation/series-faults.json .fork-scratch/mutations/<run>
python3 tools/mutation/campaign.py tools/mutation/series-faults.json .fork-scratch/mutations/<run> --suite batch
```

Run it from the checkout root, on a COMMITTED tree: every fault is applied in its
own `git clone --local` of HEAD, so the checkout is never edited and
uncommitted work is not what gets judged. It needs docker for the suites that
run in the pinned images (the ESLint, Clojure and browser suites), and the
browser harness's `node_modules` installed (`run.sh` installs it).

The evidence stays in the gitignored `.fork-scratch/mutations/<run>/`:
`report.json` records each fault's exact edit, the command, exit code, duration,
log path and digest, the lines that matched and the outcome, plus the baselines
and the checkout fingerprints taken before and after.

## What a kill is

A fault is KILLED only when its suite exits non-zero, not by timeout, and the log
names BOTH the assertion that had to fail (`kill`) and its `control`. A red that
names neither is INVALID, never a kill: a crash, a syntax error or a timeout
proves nothing about the clause. A mutation whose anchor does not occur exactly
once, or whose edit did not land, is invalid too. The campaign passes only with
every fault killed, both baselines green and the checkout unchanged; a dirty
tree is refused before anything runs.

What `control` proves differs by suite, and that is deliberate:

- the shell suites print every case, so the control is a NEIGHBOURING case
  printed as passing;
- the browser harness stops at its first failure, so reaching the named
  assertion is itself the evidence that every assertion before it passed; its
  control requires that failure to be a FAIL, not an ERROR;
- the Clojure suites print only failures, so their faults declare `only`: every
  `FAIL in (…)`/`ERROR in (…)` the log names must be one of the tests that
  assert the clause, and the control is a positive test and assertion count.

Exit codes: 0 every fault killed; 1 a fault survived; 2 the evidence cannot be
trusted (an invalid fault, a failed baseline, a changed checkout) — this
outranks a survivor; 3 CANNOT RUN. A timed-out suite's process group is killed;
a container it started is not, so the docker suites' timeouts are generous.

## series-faults.json

Scope: the gate-viewer series — the private-name scan and the hook's handling of
it, the JavaScript lint lane, the acceptance entry point, the visual-review
batch resolver, `tools/uber.sh`'s offline registry seed and `--network`
handling, the admission refusal diagnostics, and the viewer's focus handling,
refused-page controls and contrast CSS.

Each `excluded` entry names what is out of scope and why: verification surfaces
(the campaign mutates the behaviour they protect, never the tests), a
measurement probe nothing is gated on, generated projections, and faults proven
equivalent, with the argument recorded beside them. An equivalent fault is not a
kill and not an accepted survivor; it is a wrong fault model, removed.
