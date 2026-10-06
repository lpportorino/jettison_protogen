# Manual renderer-gen schema mutation campaign

Run this tool explicitly at an epic boundary, or during planned idle time, when
a change touches the generator contracts it selects: the pretty printer's key
comparator and printable value domain, the palette proposal scanner's
instrumented root, or pretty's spec-coverage enrolment. It is not a push gate or
an automatic background job. The selected fault catalog has 11 entries; the rest
of renderer-gen, the renderer and unrelated mutation operators are outside this
campaign's scope.

```sh
make -f renderer.mk proto-classes
python3 renderer/tools/renderer-gen-schema-mutations.py --out <fresh-dir>
```

Run both from the repository root in the official container. The evidence
directory must not exist; inside the checkout it must sit under
`.fork-scratch/`, which is also where it has to go under `tools/uber.sh`, since
the container's own temporary directory is discarded with it. The classpath is
the maintained `:test` alias of `tools/renderer-gen/deps.edn`, resolved once
with `clojure -Spath -M:test` and recorded; `target/proto-classes` must already
exist, and the tool refuses rather than building it.

Each selected fault must match one exact source anchor before anything runs. A
fresh worker copy of `src/`, `test/` and `tests.edn` takes that one change and
nothing else. A separate JVM first proves the worker classpath resolves the
mutated file itself, by origin and digest. The focused kaocha selection then
runs — all of `lvgl-codegen.pretty-test` plus the palette scanner's
root-contract test, seed `20261003` — and, in its own JVM, the one-test
neighbour `scalar-and-diff-control`. A kill requires a non-zero exit, exactly
one footer carrying the frozen population (7 tests, 44 assertions) and a failure
count equal to the FAIL lines printed, the fault's declared test among them, no
error marker, an unchanged worker, and the control passing at its own frozen
population (1 test, 13 assertions). Compile and load errors, an errors or
pending column, a moved count, an empty selection, crashes and timeouts receive
no kill credit. A survivor needs the same proof as a kill — a green focused
run at the frozen population, a valid binding, the control passing and an
unchanged worker — so a mutation that never reached the code is invalid, not a
survivor. Any survivor or invalid outcome fails the campaign.

Before any fault runs, the baseline must pass in both forms — the maintained
`clojure -M:test` alias and the worker's `java -cp` invocation over the real
project — with identical footers, and the control must pass. Nineteen canaries
must then be refused: four real processes (empty, compile-shaped, crashing, past
its deadline); six corruptions of the real passing report (a zero or drifted
population, a second footer, an errors column, an error marker, a failing exit);
three real kaocha runs (a syntax error, a namespace that throws while loading, a
focus naming no test); and six corruptions of the first real kill (a zero exit,
the wrong test, a count mismatch, assertion drift, an error marker, a second
footer). The baseline runs again at the end. Every input — the selected sources
and tests, the renderer-gen `src/` and `test/` trees, the shared resources and
proto classes, every classpath jar and both drivers — must hash as it did at the
start, and `java`, `clojure` and `python` must report the same versions and
binaries. `report.json` keeps the selection, commands, populations, log digests,
timings, the seed, each fault's source digests and the canary verdicts; each
run's full log sits beside it.

The process supervisor is the native API probe's, which bounds each run and
reaps its process group. Every run is a fresh JVM, so no mutant state survives
into the next one. These are selected first-party faults, not a claim to mutate
every program change in the generator.
