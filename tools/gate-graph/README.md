# Execution graph core

Source draft, not an accepted release. JVM tests and pinned formatting/native
kondo pass; the EDN admission boundary has an attributed mutation campaign.
Full CLJS/browser parity, broader mutation coverage and real CI-scale acceptance
remain. Version 1 is still under development; native test DAG execution is
available, while full process/cache graph joining and consumer adoption remain.

The library consumes data supplied by its caller. It has no repository
discovery, private namespace dependency, container naming convention or private
fixtures. Source/graph/query contracts are CLJC; the current test entrypoint
runs on the JVM. Consumer inventories, commands, receipts and observations stay
with the consumer.

## Per-test observations

Local consumer toolboxes load a graph with
`(gate.report-io/read-graph! path gate.admission/default-limits)` and prepare it
with `gate.query/prepare` or `gate.diff/prepare`. The reader bounds actual file
bytes before strict UTF-8 decoding and the existing bounded EDN/invariant checks.
Callers can lower admission limits. Nonregular files and symlink leaves refuse;
trusted parent directories are not a filesystem sandbox. File failures contain
only a closed code, while parser and graph diagnostics retain their shared shape.
Return query/diff pages to an LLM instead of returning the full prepared index.

`gate.clojure-test/inventory` discovers expected test vars from explicitly supplied,
already loaded namespaces before execution. `gate.clojure-test/run!` preserves
normal `clojure.test` fixtures, namespace hooks and reporters through dynamic
bindings. It records each invocation, retry and nested invocation separately.
Joined futures retain their assertion owner; nested observers remain independent.
No global test function is replaced. A missing test, fixture assertion, acquisition
limit or thrown runner prevents passing coverage. Empty assertion work also refuses.

```clojure
(gate.clojure-test/run!
  {:run "suite-run" :gate "gate/unit-tests" :label "Unit tests"
   :source-digest judged-source-sha256 :max-tests 1000 :max-assertions 100000}
  '[example.unit-test])
```

The caller supplies and verifies the judged-source digest. Limits bound retained
records and counts, not arbitrary test execution; use a process/container deadline.
Tests must join asynchronous work before returning. Detached assertions are outside
this profile. Invocation intervals include reporting and exclude surrounding
fixtures. Raw expected/actual values and exception details stay in normal test
reporting, rather than entering the closed observation.

`gate.test-graph/project` validates observation consistency and produces the same
canonical graph accepted by the drill, comparison and aggregation APIs. The bounded
EDN admission target is `:test-observation`. Counts distinguish entries, all
assertions, passed assertions, failed assertions and error reports. Assertion counts
belong exclusively to their dynamic invocation; acquisition overflow retains lower
bounds as `:partial`. Overlapping aggregation still needs explicit pair evidence.
Elapsed time does not imply CPU, instruction counts or background load. The graph
has a local monotonic origin. The native test DAG runner below anchors it into
the coordinator's exact same-JVM clock.

Recorded Docker evidence after splitting the graph and inspection contracts:
**225 tests / 8,058 assertions**, plus the cold parser check. The source roster
includes 38 JVM namespaces; the test entrypoint checks docstrings and function
contracts before executing the suite.
An additional 100 seeded Malli-derived count trials use independent verdict and
metric oracles; another 100 seeded offset trials use independent integer-duration
and ownership oracles. The preceding checkpoint caught twelve isolated
observer/projection/publication faults with passing controls and full baselines.
Clock/batch and refreshed observer/coordinator campaigns now catch 29 selected
faults with independently audited frozen source/log fingerprints. Subsequent
containment-harness edits are outside that snapshot. Run the observer campaign
with `clojure -M:test:test-campaign OWNED_NEW_OUTPUT_PARENT`. Canonical Docker
module runs pass the same suite; corrected containment acceptance passes 11 tests /
100 assertions. A matching CLJS rebuild and offline Chromium combined-graph proof
pass with zero external requests/errors on their recorded source snapshot. Rebuild
and rerun the browser checks after changing shared contracts or viewer code;
historical results are not whole-module mutation coverage or current-build proof.

## Source-bound test command

The ordinary `clojure -M:test` command now uses this observer. Its cold parser
regression runs first inside the same capture, followed by the full namespace
roster. The repeated var has two distinct invocation IDs and sequential attempts;
it is not collapsed or double-attributed. The recorded viewer proof has 224 test
invocations and 7,979 assertion reports, including the cold regression.

`gate.test-artifact/run!` is the shared publisher for trusted test commands. It
hashes supplied ordered source roots before/after execution, retains changed-source
observations while refusing coverage, round-trips both records through bounded
admission and writes create-only `tests.edn` and `graph.edn`. Supply the actual
executing classpath plus other files the tests read. Output must be outside all
directory roots, including parent symlink aliases. A failed write fails the command;
the two-file publication is not transactional. Loaded code remains trusted immutable.

From this module:

```sh
clojure -M:test
clojure -M:test NEW_REPORT_DIRECTORY
```

Default reports use fresh ignored `.gate-reports` directories. Existing reports
are never overwritten. The command now uses `gate.test-batch/run!`, described
below; raw per-test records are under its hashed gate directory. The preceding
checkpoint exercised sixteen bounded inspection checks on two actual local-clock
reports. Combined graphs now pass 23 inspection checks. This command is module-test
evidence, not a full repository CI report.

From the repository root, `make -f lint.mk gate-graph-test` runs the suite and
the regular coverage canary; both CI and the pre-push hook invoke this target.
The canary removes the coverage guard in a disposable module copy, requires the
named test to fail by assertion with zero errors, and requires an independent
valid-cache control to pass. Full baselines run before and after the fault;
source fingerprints and closed EDN evidence are retained beneath
`tools/gate-graph/.gate-reports/canaries`. Run it alone from this module with
`clojure -M:test:canary .gate-reports/canaries`. Broader campaigns remain explicit
commands and are not replaced by this selected regular canary.

## Clocked native test DAGs

`gate.test-batch/declaration` discovers an independent expected test-var inventory
and declares an always-run native gate. Its `:command` is a native callable
descriptor, not subprocess argv. Loaded namespace rosters and synchronous runner
functions are supplied separately from serializable declarations:

```clojure
(let [namespaces '[example.unit-test]
      gate (gate.test-batch/declaration "gate/unit-tests" "Unit tests" namespaces [])]
  (gate.test-batch/run!
    {:run "example-run" :key "chain/tests" :label "Test chain"
     :coordinator {:jobs 2 :claims {}} :max-tests 1000 :max-assertions 100000}
    [gate] [(:id gate)]
    {(:id gate) {:namespaces namespaces
                :runner (fn [] (apply clojure.test/run-tests namespaces) nil)}}
    (gate.test-artifact/classpath) "NEW_REPORT_DIRECTORY" (atom false)))
```

Supply every actual classpath and other read input in the source-root vector.
Enrollment must cover the entire declaration roster, including deselected gates.
The coordinator enforces concurrency, resource claims and prerequisites. Gates
bind to one expected source digest and verify their actual before/after bytes.
This trusted native profile has no network/process isolation, receipt reuse,
arbitrary-code deadline or detached-thread cleanup. `:cache :content`, denied
network policies and subprocess-style input/output/environment policies refuse.
Cancellation stops queued work and joins running synchronous callbacks.

The fresh output root contains `definitions.edn`, `batch.edn` and the combined
`graph.edn`. Each `gates/SHA256_GATE_ID/` directory holds `tests.edn`, its local
`graph.edn` and `anchor.edn`. File publication is create-only, not transactional;
there is no recovery journal for acquisition or publication failure. A passing
batch cannot promote an incomplete graph to a passing report.
The run interval covers coordinator execution. Initial setup and final combined
projection/publication are outside that interval; it is not yet a full CI envelope.

`gate.clock/start!` creates one immutable native context, passed unchanged to
`gate.coordinator/run-clocked!` and `gate.clojure-test/run-in!`. `ClockedTests`
stores only its clock ID, exact decimal offset and closed test observation.
Signed native origins/ownership objects never enter EDN. Tick subtraction handles
long wraparound for ordered marks less than 2^63 ns apart; contexts cannot align
other JVMs, subprocess timers or wall clocks. Nanosecond precision does not assert
timer resolution. The unpublished version-1 Batch now requires `:clock`; older
checkpoint records without it are historical evidence, not current Batch inputs.

`gate.batch-graph/project` reconciles the entire plan and dispatch roster, verdict
coverage, prerequisite timing, capture bindings and containment. Actual tests are
under their suite gate, then the adapter boundary, then the run. Adapter time
includes inventory/source checks/publication and remains separate from test time.
Cached, deselected, blocked and cancelled work has instantaneous decisions;
missing acquisition has a refusal and marks the graph incomplete. Declared
dependencies remain visible, including invalidation relations. Only observed
causal edges impose timing; invalidation edges cannot assert observed causation.
Independent capture sources remain distinct, so cross-source or unproved
overlapping aggregation refuses. This profile does not invent CPU or background
observations. Run attributed controls with
`clojure -M:test:batch-campaign OWNED_NEW_OUTPUT_PARENT`.

## Clocked subprocess supervision

`gate.process/run-clocked!` accepts the ordinary explicit process request,
cancellation atom and the coordinator's unchanged `gate.clock` context. It
returns a closed `ClockedProcess` with `:clock`, `:offset-ns` and the complete
process observation. Offset and elapsed duration use the same initial tick.
The interval covers invocation supervision: launch, output handling and cleanup.
Kernel process lifetime, CPU consumption and internal tests need their own
observers. The existing process budgets and numeric-jobserver refusal apply.

`gate.process-graph/project` takes the same options, declarations, selected roots
and Batch as the native test projector, followed by a vector of `ProcessCapture`
records. Each record supplies `:run`, `:gate`, `:label`, `:source-digest`, actual
`:command` and `:cwd`, the clocked `:process`, and independently acquired
`:coverage`. A process exit code alone cannot supply the work witness. The
portable `gate.verdict/process-result` is shared by execution and projection.

The projector verifies declaration/clock/source/command identity, adapter
interval containment, verdict and coverage agreement, unique capture enrollment
and a finite source budget. It preserves typed dependency edges and their
execution/decision endpoints. A started command gets its supervision interval;
a failed launch gets a refusal, without execution time. Missing captures,
truncated logs, I/O failure or unknown cleanup make acquisition incomplete.
Completeness describes the declared command scope; descendant sampling has no
containment guarantee and cannot establish a complete subprocess forest.

The graph's capture-source digests bind the raw records, including exit status,
PID, bounded log digest and cleanup evidence. Retain these records alongside the
graph; the current viewer does not yet expose their full contents. No test,
CPU or instruction measurements are inferred. Public EDN admission targets
`:clocked-process` and `:process-capture` accept only these closed records.
Mutation controls run with
`clojure -M:test:process-graph-campaign OWNED_NEW_OUTPUT_PARENT`.

## Capture-to-report path

`gate.process-batch/run!` joins declared command DAGs, bounded subprocess
supervision and source/input snapshots. Supply options (`:run`, `:key`, `:label`,
`:coordinator`, `:input-limits`, `:process-limits`), the declarations, selected
roots, exact bindings, actual controller/adapter source roots, a fresh output
directory and a cancellation atom. Each binding supplies `:directory`, the exact
`:environment` map, `:toolchains` digests, optional file `:stdin` and `:witness`.
Bindings must enroll every declared gate, including deselections.

A witness callback receives the declaration, per-gate report directory and actual
process observation, and returns independently observed coverage or nil. It must
be bounded and cooperative. The explicit `:command-invocation` witness instead
counts one launched command; pair it with `gate.process-batch/command-expectation`
over the exact argv/cwd. This proves invocation only. Coverage declarations and
observations require `:unit` (`:commands`, `:checks` or `:tests`); verdicts and
cache admission refuse mismatched units even when digests/counts agree. This is
an unreleased version-1 contract change; old development captures are historical
evidence and cannot be passed unchanged to the new APIs.

This native profile requires always-run gates, allowed network access and no
published outputs. It does not establish cache safety or isolation. Initial
source failure refuses before launch. Complete declared-input observations,
including any redirected stdin, are required before execution; changed inputs or
controller bytes cannot pass. Source, witness and per-gate publication failures
remain inspectable alongside any already observed process. Failed prerequisites
and pre-cancellation cannot fabricate execution spans.

The output contains `definitions.edn`, `batch.edn`, `graph.edn`, and `report.edn`
with the raw captures. Hashed per-gate directories contain `command.log`,
`inputs-before.edn`, `inputs-after.edn`, and `process.edn`. Publication is
create-only, not transactional. An I/O failure may leave incomplete files and
must not be treated as a passing run. The graph viewer still accepts `graph.edn`;
it does not yet embed all raw captures from `report.edn`. Run selected acquisition,
stability and unit faults with `clojure -M:test:process-batch-campaign OUTPUT_PARENT`.

`gate.trace-io/load-journal` reads a **quiescent** public gate-trace v1 journal.
It bounds files (256,002), individual bytes (1 MiB), total bytes (128 MiB), JSON
depth and token lengths, requires strict UTF-8 and one physical JSON line, and
refuses duplicate JSON keys, unknown layouts and non-regular final files.
It is not a transactional snapshot of an actively mutating or hostile directory.
Annotation files are currently an explicit `:trace-unsupported-annotation`
refusal. JVM counters must fit nonnegative signed 64-bit integers; the original
capture format permits larger naturals. Unit conversion uses exact arithmetic.

`gate.trace-import/project` takes the loaded journal and a caller-supplied
`[{:name "compile" :key "gate/compile"}]` inventory. Inventory is never inferred
from observed tasks. It checks identities, start/final pairing, exit consistency,
clock order and the normalized graph's global invariants. A final run stamp does
not promote unfinished spans to success. Capture IDs, semantic keys and attempts
remain distinct. SHA-256 identities cover journal bytes and the declaration.
Closed `gate.trace-contract/Failure` errors have a code, relative path and up to
32 schema issues; raw rejected values are not included. This projection retains
three wait4 observations per measured span: user/system CPU and individual RSS
peak. Each covers the work interval and remains **inclusive**. Other resource,
exit, host and toolchain details remain in the original journal. The importer
does not invent dependencies, disjointness assertions, background load or PMU counts.

The HTML uses Hiccup 2.0.0 and a shadow-cljs 3.5.3 release bundle. All graph data,
CSS and JS are embedded; no server or sibling files are needed to view it.
The browser uses the same CLJC admission, canonicalization and bounded query
functions. Click a task to inspect exact EDN measurements, or `Children` to open
its companion timeline. Logical task lanes show concurrency; widths use elapsed
time. Geometry alone uses floating point, after exact origin subtraction.
Each drill page permits 200 rows, 128,000 visits and 1 MiB, with continuation.
This initial view does not yet provide cheap-entry folding, dependency connectors,
search, background overlays, aggregate controls or CI-scale performance proof.

Run the scoped public demonstration from the checkout root:

```sh
bash tools/uber.sh 'cd tools/gate-graph && clojure -M:shadow release viewer'
bash tools/uber.sh 'bash tools/gate-graph/test/capture-public.sh'
bash tools/uber.sh 'cd tools/gate-graph && clojure -M -m gate.trace-cli "$(cat ../../.fork-scratch/graph-logs/journal-path.txt)" ../../.fork-scratch/graph-report ../../.fork-scratch/gate-viewer/main.js graph-tests gate/graph-tests capture-tests gate/capture-tests'
```

The output directory must be new; existing artifacts are not overwritten.
The two real, independent suites run concurrently and keep separate logs under
their unique run ID. This is a scoped integration demonstration, not the full
public CI roster or a replacement for a shared declarative runner.
The CLI's release bundle is trusted local build output; journal input is bounded.

`test/offline-report.mjs` accepts Playwright's module path, the report path and
a screenshot destination. With Playwright 1.60.0 / Chromium 148.0.7778.96 in a
network-disabled container it proved file navigation, drill/back actions,
measurements, overlapping geometry, and agreement between the browser's canonical
digest and the JVM EDN artifact. Zero external requests/page/console errors.
The browser runtime is not yet enrolled in the public base image or gate roster.
Full cross-platform parity, keyboard/hostile-DOM/browser mutation tests and
scale acceptance remain; one real artifact is not a complete parity suite.

## Model and schema discovery

`gate.schema/registry` names closed Malli graph and inspection schemas, retaining
their `:gate.contract/*` schema identifiers. `gate.contract` owns graph records;
`gate.inspection-contract` owns queries, aggregation and comparison. The document has a
run declaration, provenance sources, resource identities, execution/decision
nodes, event-phase causal edges and measurement observations. All normalized
maps are closed; no arbitrary metadata or `:any` fields are accepted.

The additional `gate.run-contract/registry` describes gate definitions, input
snapshots, dependency terms, coverage, receipts and scheduling decisions.
`gate.plan/schedule` validates the entire DAG before deriving the entrypoint's
execution prerequisite closure. `:requires` and `:produces` require successful
dependency evidence; `:after` orders work without invalidating on its input key;
`:invalidates` carries key evidence without forcing execution. The pure scheduler
orders declarations; the coordinator below enforces runtime prerequisites through
an adapter. The production process/cache integration remains unfinished.

`gate.coordinator/run!` now dispatches the selected DAG through a caller-supplied
adapter. It uses an explicit fixed worker pool and submits only available slots.
Options are `{:jobs 2 :claims {"compile" ["compiler-cache"]}}`; gates sharing a
claim cannot overlap, while independent resources remain concurrent. Requires
and produces edges need successful prerequisites; after edges require termination
only; invalidation edges do not force execution. Every declaration receives a
terminal record, including blocked and deselected work, in deterministic order.

The backend takes `(gate cancellation-atom)` and returns the closed
`gate.run-contract/WorkResult`. A passed or cached result needs matching declared
and observed coverage identity and the minimum count. Unexpected assertions or
exceptions become compact failures. The backend still owns independent work
witnesses, proper cache validation and actual process/test instrumentation.
A cached result's coverage is historical, not newly executed tests.

Cancellation stops pending dispatch and asks running adapters to clean up; the
coordinator joins them before returning. Caller interruption requests the same
cleanup and restores the interrupt flag afterwards. Adapters must cooperate.
Arbitrary in-process code, System/exit, native hangs and detached children need
an isolated process/container adapter; this library does not forcibly stop them.
Each adapter interval includes lookup/dispatch overhead and is not an execution
span. Undispatched decisions have an instant and no execution interval.

The coordinator's real public acceptance harness is:

```sh
# Supply the actual executing image's SHA-256 hex (without the sha256: prefix).
bash tools/uber.sh 'bash tools/gate-graph/test/capture-coordinator.sh IMAGE_SHA256_HEX'
```

It runs both suites through the coordinator, live attempt API and T1, with
separate logs, canonical `batch.edn` and per-suite `*.attempt.edn` in the unique
capture's log directory. The latter are round-tripped through bounded admission
and retain live source membership, byte digests and reuse refusals. The supplied
image identity is a caller attestation, not automatic image discovery. Its positive work
witnesses describe two whole-suite units, not individual tests. The shared local
process backend bounds streams and cancellation/time limits for these trusted
commands; it does not establish process-tree/network isolation or pipe-jobserver
fidelity. Its explicitly passed current environment is insufficient for reuse. The
attempt API explicitly refuses lookup/publication with `:unproven-isolation`.
Each suite also emits bounded-admitted `*.process.edn` with exit, log digest,
truncation and cleanup evidence. Attempt/process records are not yet joined
into the rendered execution graph.
The production resolver/cache/backend integration, per-test spans and consumer
adoption remain required.

`gate.inputs/observe!` supplies bounded live file/tree/Java-NIO-glob observation.
Each declared selector retains its membership, including optional empty sets.
Explicit `:exclude` globs prune directories; exclusions are keyed and their order
is normalized. Overlapping selectors hash shared files once. Every observation
streams actual bytes; equal size and mtime never substitute for content hashes.
The observer checks file attributes around each read and rescans membership.
Limits cover unique files, bytes read, traversal entries and depth. Missing
required inputs, unknown toolchains, unreadable files and exhausted budgets
produce closed incomplete evidence, never a truncated complete snapshot.

Symlinks and special files currently refuse. This profile is neither Git
pathspec discovery nor a language dependency resolver. Environment/toolchain
evidence comes from the caller; trusted local filesystem traversal does not
freeze source files or defeat adversarial directory races and edit/revert cycles.
`outputs!` observes exactly declared regular output files and returns nil when
they cannot be established; an empty vector means no outputs were declared.

`gate.attempt/run!` connects those observations with lookup, forced execution,
coverage judgment, post-run dependency observation, receipt admission and atomic
publication. A closed AttemptObservation keeps the work outcome separate from
the admission and publication outcome: passing but unrecordable work stays passed.
Missing or failed post-run dependency evidence prevents reuse. Cached coverage
remains historical. Cancellation cannot be relabeled as a hit. Receipt admission's
`:recorded` status means a candidate was earned; the separate `:publication`
field says whether storage succeeded or found a conflict.

Request `:isolation-verified?` is an explicit trusted runtime attestation. False
disables both consumption and publication of receipts. True requires the runtime
to establish stable complete inputs, the effective declared environment and
denied network; this API and the mutable-workspace observer cannot establish it.
The synthetic lifecycle tests attest their controlled backend, not arbitrary
shell/container isolation. A concrete container profile is described below;
wiring its facts into receipt authority, effective dependency discovery and
integrated changed-gate selection remains required.

`gate.process/run!` executes exact argv with an absolute executable and clears
the inherited environment before installing the supplied names/values. Nil
values remain absent. Stdin is closed or redirected from a bounded regular file,
preserving caller-owned hook input for replay. Cwd and stdin refuse symlink
components beneath the trusted root. Logs are create-only, combined stdout/stderr
byte streams with a SHA-256 and exact retained-byte count. At most the declared
byte limit is written; excess output terminates the command with `:output-limit`.
No text decoder or unbounded StringWriter sits on the capture path.

Timeout and cancellation use monotonic time and poll alongside output. Cleanup
kills the root and observed descendants, has its own deadline, records incomplete
cleanup and restores caller interruption. The live-handle bound is separate
from cumulative observed count; finished children do not consume slots forever.
The cumulative count is sampled, not a complete process census. A full counter
or live-handle limit refuses explicitly. Work left alive at normal exit cannot
become a passed gate merely because cleanup later kills it.

This profile reports `:containment :none`: detached children may escape descendant
snapshots. Local filesystem I/O assumes a responsive trusted filesystem. No
filesystem/network isolation or cache eligibility follows from a process exit.
`work-result` requires separately observed coverage as well as exit zero and
clean termination. Numeric Make jobserver descriptors are refused before launch;
FIFO jobserver policy and a contained process/container backend remain required.
Process records have a closed CLJC schema and bounded `:process-observation`
admission; they contain no argv, environment values or native exception messages.

`gate.snapshot/materialize!` copies verified declared bytes into a new owned
directory. It checks copy bytes against the observation, bounds bytes copied,
normalizes file permissions/mtime and verifies resulting membership. Output
mount points cannot hide inputs, overlap or leave declared outputs unowned.
The source workspace can subsequently change without changing the copied bytes.
Read-only execution is enforced by the container mount; host actors must respect
ownership of the copy. Directories keep normalized owner-write permission so
the owner can later remove its snapshot without privilege. Failed copies remain
owned scratch for inspection/cleanup and never yield usable snapshot evidence.

`gate.container/run!` implements a trusted local Linux Docker profile: immutable
image ID, copied source mounted read-only, declared output roots separately
writable, denied network, dropped capabilities, cleared image environment,
read-only image root and bounded tmpfs/memory/CPU/PIDs. `/tmp` is explicitly
noexec. Image-declared volumes refuse. The controller uses create → start →
remove phases so cancelling start cannot race a second create. Bounded phase
logs, the owned container name, output digests and closed ContainerObservation
are retained in a fresh scratch directory. Ambiguous creation/removal remains
`:cleanup-unknown`; the name file supports recovery, but a production recovery
service is not implemented. Inputs, outputs and logs stay consumer-local.

`profile` and `runtime-evidence` bind image, engine-source bytes, controller
kernel/architecture, container resource limits and output roots into declared
toolchain evidence. The gate must declare `image` and `container-profile`, deny
network and supply exactly its declared environment. Engine source resources
must be bundled even for AOT use. The daemon must share the controller's local
kernel/filesystem and see identical absolute mount paths; remote proxies and
Docker Desktop are outside this profile. Inherited numeric and FIFO Make
jobserver capabilities refuse rather than silently losing their coordination.

The low-level container API retains outputs in owned scratch. The composed
`gate.contained/run!` API below publishes verified outputs into the workspace.
The composed path verifies runtime identity and holds cooperative output claims
before enabling reuse. Independent dependency witnesses and lifecycle recovery
still belong to the runner/consumer integration. Deterministic gate
semantics are still required: clock/random inputs are not virtualized, and bind
output directories do not have enforced disk quotas. This profile is not a
general hostile-code sandbox.

Executable input identity now reads POSIX execute permission bits rather than
`Files/isExecutable`: a noexec mount must not hide a mode change. This was found
by running the full public module suite inside the contained profile, fixing
the failing mode/key assertion and rerunning it successfully.

The explicit host-side Docker acceptance commands are:

```sh
bash tools/gate-graph/test/container-acceptance.sh IMAGE_SHA256_HEX
bash tools/gate-graph/test/container-acceptance.sh IMAGE_SHA256_HEX mutations
```

The pinned controller alone receives the Docker socket. Child gates receive no
socket. Acceptance stages the actual public module and resolved classpath bytes,
preserving dependency order, then executes its entire JVM suite offline against
read-only inputs. This dependency preparation is outside the contained gate;
it is not counted as denied-network execution. Tests also exercise workspace
changes, cancellation with a detached child, output overflow and nonzero exits.

### Contained attempts and generated outputs

`gate.contained/run!` takes the same request as `gate.container/run!`, closed
attempt options, an independent coverage observer, a dependency observer and
the cooperative cancellation atom:

```clojure
(contained/run! container-request
                {:cache-directory cache-directory :run "run-1" :attempt "compile-1"
                 :dependencies [] :force? false}
                observe-actual-coverage observe-current-dependencies cancellation)
```

The coverage observer receives `(gate, container-result)` and returns a closed
Coverage record or nil. It must inspect actual work evidence such as the emitted
test inventory; command exit zero is insufficient. It runs only after a known
container has exited successfully and has been removed. The dependency observer
returns current producer result terms after execution. Consumer adapters own
these witnesses and their private inventories.

The result separates `:directory`, a consumer-local retained scratch path, from
`:observation`, a canonical ContainedObservation. That record contains `:attempt`,
`:container` and `:output-publication`. Decode it using the bounded
`:contained-observation` EDN target. Its command result, artifact installation
and receipt admission remain distinct. A child can exit zero while its attempt
fails with `:input-unstable` or `:output-publication`; dependent gates must use
the attempt's work result. Records omit absolute scratch paths, but declared
filenames/environment and outputs still belong to the consumer.

`gate.attempt/run-observed!` passes the exact keyed pre-run snapshot to its
three-argument backend `(gate, before-snapshot, cancellation)`. The existing
`run!` API accepts a two-argument backend. Contained completion checks that the
executed immutable copy and current workspace both match the keyed snapshot
before publishing. This catches a different copy even when mutable workspace
bytes have changed and reverted between observations.

`gate.publish/install!` verifies declared source outputs, stages every file on
its destination filesystem, verifies staged bytes and execute bits, then replaces
files with atomic moves and verifies the installed set. Symlink destinations,
changed bytes and unsupported atomic moves refuse. No unlisted file is removed.
Whole-batch publication is **not transactional**: `:partial` and `:installed`
identify the completed subset after a later move or verification fails. A partial
batch cannot pass the composed attempt. `:output-publication` is also a bounded
standalone EDN target. Temporary files receive best-effort cleanup; failed
cleanup or empty created parent directories can leave local residue. Neither
power-loss durability nor adversarial directory races are claimed.

The composed API owns cooperative claims across lookup, execution, output
installation and receipt admission. `gate.ownership` uses a fixed
`.gate-output-locks` directory in the canonical workspace, independent of the
cache directory. Claims cover sorted top-level output roots: siblings under
`out/` serialize conservatively; different top-level roots and output-free
gates can overlap. Claim wait defaults to 600,000 ms; attempt options can set
`:ownership-timeout-ms`. Waits are cancellable and partial acquisition releases
earlier claims. Errors carry `{:code :output-ownership :reason ...}` without
workspace paths. Lock files must never be deleted while runners are alive.
All writers must cooperate and the local filesystem must support advisory locks.

One JVM mutex guards each file-channel lifetime; nested acquisition on the same
thread refuses before opening a competing channel. This addresses Java's
[documented file-lock constraints](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html):
file locks alone do not coordinate JVM threads, and some systems release a file's
locks when another channel for that file is closed. Network filesystem semantics
and hostile writers are outside this local profile.

`gate.runtime/probe!` supplies a closed RuntimeObservation, admitted through
`:runtime-observation`. Its identity includes image/profile, input/process
budgets, actual Docker executable bytes, JDK library/config bytes, mapped native
libraries, ordered controller classpath bytes and JVM arguments/properties.
Files, membership and execute bits are rehashed with a shared 1 GiB byte budget;
there is no size/mtime memo. Runtime entry count is bounded to 1,024. The probe
resolves top-level JDK `lib`/`conf` directory links before enumerating their actual
bytes. Nested directory links and traversal reaching the depth limit refuse;
neither can silently omit runtime inputs.
It also binds Docker client/server versions, daemon identity/security/runtime/storage
configuration, stable CPU model/features and boot identity. Linux CPU MHz and
system load are excluded from the identity; they are not work normalization.
Only digests enter portable records; raw protocol logs remain in local scratch.

Successful probes add the reserved `gate-runtime` toolchain term to actual cache
material. Changed/unavailable post-execution identity prevents output publication
and receipt admission. Unavailable initial evidence executes uncached and records
`:unproven-isolation`. Probes use bounded CLI phases and explicit local endpoint/
config, following Docker's [version](https://docs.docker.com/reference/cli/docker/version/)
and [info](https://docs.docker.com/reference/cli/docker/system/info/) interfaces.
Contained observations retain before/after runtime records, ownership wait and
total elapsed time; cached results have no new container or coverage callback.

The controller's loaded code must remain immutable throughout a run; arbitrary
unlisted `load-file`/`eval`, externally replaced loaded libraries and adversarial
daemon changes are outside this trusted profile. Keep the controller classpath
limited to runner/adapter tooling and supply product source as gate data/inputs.
Putting all product code on the controller classpath will conservatively invalidate
all gates when any of that code changes. Absolute runtime paths and boot identity
also conservatively reduce cross-checkout/reboot reuse. Probe cost is observable
but is not yet amortized across a whole run; no low-overhead claim is made for
very cheap gates. Production batch integration must measure and address that cost
without introducing a metadata-only identity cache.

Publication/completion mutation assessment is available as:

```sh
bash tools/uber.sh 'cd tools/gate-graph && clojure -M:test:publication-campaign ../../.fork-scratch/mutations'
```

`gate.cache/input-key` hashes a versioned canonical material record, retaining
command argument order while normalizing unordered inputs, memberships,
environment names and toolchains. Keys include file paths/content/executable
mode, explicit empty membership, absent-versus-empty environment values,
toolchain identities, coverage expectations and invalidating dependency evidence.
Display labels are excluded. Snapshot completeness must account for every
selector and declared environment/toolchain term; unknown observations never
yield a reusable key. These are adapter-supplied facts: this pure layer does not
prove filesystem discovery, environment isolation or a declared network policy.

`gate.cache/decide` and `admit` distinguish forced/always-run/network-unbounded,
incomplete/unknown evidence, missing/invalid receipts, changed inputs, incomplete
coverage and changed/missing outputs. Passing execution alone is insufficient.
Receipt admission checks pre/post input and dependency identities and exact
expected/observed work identity plus a minimum count. An output digest stays
separate from input identity, and a successful result fingerprint excludes run
IDs and timing. Post-run comparison is not a filesystem freeze or an ABA detector;
input stability during execution needs the producer/runtime proof.

`gate.store` reads bounded strict EDN receipts from a trusted machine-local cache
directory (4 MiB per receipt, no-follow final files). Publication hard-links a
completed unique temporary into the key slot without overwriting another run.
Equal concurrent results retain the first receipt. Conflicting results preserve
the original and create a persistent `.conflict` marker: subsequent lookups miss
and publication refuses. This was added after a regression demonstrated that
merely reporting a conflict still allowed the old receipt to be reused.
Malformed/missing/unreadable receipts are explicit misses. Atomic publication is
not a power-loss durability guarantee. Live producer discovery, process isolation,
cache explanations in graphs and consumer adapters remain open.

`gate.diagnostic/install!` instruments registered JVM `gate.*` functions with
closed errors naming the function/phase and bounded schema paths. It omits raw
inputs and whole schemas and masks dynamic map keys. Public callers still need
bounded input admission; bounded serialized diagnostics are not a general CPU
budget for arbitrary in-memory values. The test runner checks both function
contracts and docstrings, including private function vars.

Consumers enroll their own loaded namespaces explicitly, for example
`(gate.diagnostic/install! '[example.adapter])`. These names match exactly;
`example.adapter.child` needs its own entry. The zero-argument form continues to
enroll `gate.*` only. A function's `m/=>` declaration alone does not activate
runtime checks. Call the installer after requiring all intended adapter namespaces.

The inspection acceptance command exercises actual captured artifacts:

```sh
bash tools/uber.sh 'cd tools/gate-graph && clojure -M:inspection ../../.fork-scratch/graph-report/graph.edn ../../.fork-scratch/graph-report-v2/graph.edn ../../.fork-scratch/inspection-proof'
```

It requires two complete two-gate dogfood runs and a new output directory. Ten
checks preserve actual API response EDN for gate pages, continuation, children,
measurement ownership, temporal coincidence, RSS extremum, RSS-sum refusal,
inclusive-CPU overlap refusal and task comparison. Coincidence is not causality;
cross-resource/background-load correlation remains to be implemented. The first
exercise caught wrong raw-graph arguments to `diff/prepare` and exposed oversized
Malli errors; corrected usage and compact-error regressions now cover that seam.

- A node's `:id` identifies this occurrence; `:key` identifies its semantic
  task across runs. Executions also carry an attempt number. They retain the
  actual outcome and an open or finished interval. Cache hits, deselections,
  blocked work and refusals are decision records at a single instant; they
  cannot carry an execution interval.
- `:parent` means temporal containment. Biological process ancestry or a
  dependency does not establish containment. Outliving children and persistent
  shared daemons must be represented by their actual lifecycle and relationships,
  not forced into a finished parent's interval.
- Edges connect node **phases** (`:start`, `:finish`, `:decision`). A parent
  start spawning a child start, followed by a child finish joining a parent
  finish, is acyclic. Collapsing those edges to node IDs would manufacture a
  cycle. `:observed` requires resolved ordered endpoints; `:declared` can retain
  pending dependencies whose finishing time is not yet observed.
- A run declares expected **gate** keys. A complete run must account for them
  and contain no running executions. Incomplete captures are valid data with an
  explicit incomplete state. Complete does not imply that all gates passed.
- Resources have a separate parent forest. Measurements reference resources,
  optional execution owners and provenance sources. Inclusive/shared accounting
  is retained rather than silently added across overlapping scopes.
- Optional `:partitions` record a producer's disjoint-accounting assertions:
  identity, provenance source, quantity and explicit measurement member IDs.
  They are evidence supplied by an importer, not facts inferred from different
  process IDs or sibling resource nodes. An assertion requires exclusive delta
  observations of one additive quantity. Missing/repeated members and overlapping
  coverage on the same resource are inconsistent and rejected.

All quantities and instants are canonical decimal strings, at most forty
digits. `gate.decimal` performs exact comparison/addition/subtraction in both
target runtimes; overflow and negative differences are named errors. Integers
used for version, attempt and collection budgets have finite small bounds.
Run-relative nanoseconds share one declared clock identity and exact origin.
An importer must prove clock correspondence before combining captures; this
library does not guess offsets between machines/time namespaces.

The quantity registry declares units, permitted observation forms and whether
the quantity is potentially additive. That flag is **not** proof that any two
observations can be summed. Status is measured, partial, estimated or unavailable;
unknown values and reasons are explicit. PMU metadata retains raw count,
enabled/running time, event and privilege domain. Multiplexed coverage cannot
claim complete measured coverage, and no running time cannot become zero work.
Host CPU full-pressure cannot appear as an available measurement.

## Validation and interval algebra

`gate.graph/findings` applies shape and global checks and returns at most 128
diagnostics. This is a bounded list of findings, not an exact total. Each names
a clause and graph identities. Checks include duplicate IDs, dangling sources,
parents/resources/endpoints, parent/resource/event cycles, containment, timing,
execution/decision state, inventory completeness and measurement semantics.
`require-valid!` refuses graphs with findings. Shape contracts and global
invariants are different checks; neither replaces the other.

`gate.interval/union` preserves occupied intervals and gaps. `summarize` returns
count, first/last bounds, occupied union length, envelope length and summed
duration separately. For intervals [0,10), [5,20), [25,30), those lengths are
25, 30 and 30 respectively. CPU service can exceed wall duration. Neither this
module nor the quantity registry computes wall-minus-CPU waits or sums RSS
peaks. Measurement intervals must cover the **work being counted**, not merely
the time spent reading a counter. A lifetime `wait4` observation needs the
child's work interval; a counter delta needs both endpoint captures, scope
continuity and reset checks. Those importer proofs remain outstanding.

Library refusals use `gate.contract/Failure` as their closed `ex-data` contract.
Malli instrumentation's own argument errors are a separate boundary: CLI/tool
adapters must validate incoming requests and normalize their error response.
Use `gate.admission/decode` for raw EDN admission rather than a general reader.
It accepts explicit limits and one target: `:graph`, `:query-request`,
`:aggregate-request`, `:diff-request`, or `:value` (the canonical value union).
The graph target also enforces global invariants.

```clojure
(require '[gate.admission :as admission])
(admission/decode "{:select {:op :nodes}, :budget {:visits 100, :rows 10, :bytes 4096}}"
                  :query-request admission/default-limits)
```

The EDN profile admits vectors, maps with registered keyword keys, strings,
registered keyword values, booleans, nil and canonical integers from zero to
2,147,483,647. Exact quantities remain decimal strings. ASCII whitespace,
commas and semicolon comments are accepted. Tags, lists, sets, reader macros,
symbols, duplicate keys, unknown keywords and trailing forms are refused.
Input never reaches evaluation or keyword interning.

Limits cover UTF-8 source bytes, nesting, total values (including map keys),
collection members/map entries, decoded UTF-16 string units and token units.
Checks precede retained token/collection growth. Parsing uses an explicit
bounded frame stack, including under cold Malli instrumentation. The source
must contain valid Unicode; explicit EDN `\\uXXXX` escapes preserve UTF-16
units, including lone surrogates, without lossy replacement. Refusals carry
stable `:code`, `:offset` and `:offset-unit :utf16` fields. They do not echo
input. Richer bounded schema-path diagnostics remain to implement.

The caller already owns the input string. File/network adapters must bound
I/O before allocating it; this API does not establish file admission or a
wall-clock latency guarantee. A `:value` target checks the union's shape; use
`:graph` before treating a decoded graph as globally valid.

## Canonical evidence and bounded queries

`gate.canonical/normalize-graph` orders node records by semantic key/attempt/ID
and identity-indexed collections by ID. `encode` sorts object keys and emits
ASCII EDN with explicit Unicode code-unit escapes; this avoids printer bindings
and platform UTF-8 replacement behavior changing a digest. It counts tokens
before retaining them beyond the requested encoded-byte bound. The result is
lossless data, not a pretty report. SHA-256 identities cover those complete
bytes. Actual observations and run IDs legitimately change between runs.

`gate.query/prepare` validates and indexes one immutable graph and hashes its
canonical bytes, with a 128 MiB encoded limit. It is an ingestion operation,
separate from per-query scan budgets. Keep the returned context within the
application; do not accept a caller-supplied prepared context as input.

The initial data-only selectors are:

| Selector | Required fields | Meaning |
| --- | --- | --- |
| `:nodes` | `:op` | All nodes in deterministic ID order |
| `:children` | `:op`, `:anchor` | Immediate children; a nil anchor selects roots |
| `:window` | `:op`, `:interval` | Intersection with a half-open run-relative interval |
| `:measurements` | `:op` | Observations in deterministic ID order |

Node selectors accept an optional closed `:where` map with equality filters on
`:kind`, `:outcome` and `:key`. Measurement filters accept `:node`, `:resource`,
`:quantity`, `:form` and `:status`; `{:node nil}` selects unowned observations,
including host backdrop, without attributing them to tasks. There is no
expression reader or eval surface. Deeper
traversal, grouping, top/explanation operations and schema-derived tool
documentation remain outstanding.

`page` takes a prepared context and this request shape:

```clojure
{:select {:op :children :anchor "root"}
 :budget {:visits 100 :rows 10 :bytes 4096}}
```

Results contain `:artifact`, `:rows`, `:visited`, `:stop` and `:next`. Scanning
counts nonmatches too. The encoded response, including its continuation, must
fit the byte budget. A result stopped by bytes can have no rows; increase the
budget and retry its continuation. The returned scan counts describe this page,
not a full total of matching nodes.

Continue by passing `:next` as `:cursor`. A cursor binds the complete artifact
digest and semantic selection, so changing observations or filters refuses it.
Budgets can change between pages. End of scan has `:stop :complete` and no next
cursor; the other stop reasons are visit, row and byte limits. Limits are at
most 128,000 visits, 1,000 rows and 1 MiB response bytes. These are deterministic
work/output limits, not a wall-clock latency guarantee. Ingestion/validation,
instrumentation cost and browser responsiveness still require measurement.

## Measurement aggregation

`gate.measure/aggregate` accepts the same prepared context and an explicit,
bounded selection. For example, after drilling the measurements for a task:

```clojure
{:op :sum
 :measurements ["cpu-before" "cpu-after"]
 :budget {:pairs 100 :bytes 65536}}
```

Supported operations are `:sum` and `:maximum-observation`. Results retain the
artifact digest, selected IDs, quantity, exact decimal value, checked-pair
count and basis. `:scope :selected-observations` is deliberate: success proves
the selected arithmetic finished, never that every task or all work was captured.

- Both operations require known, measured observations with identical quantity,
  form, method, provenance source and accounting mode. PMU event and privilege
  domain must match. Partial/estimated/unavailable observations refuse the whole
  selection; their individual values remain available through drilling.
- `:sum` requires additive delta quantities. Every pair must have separated
  work intervals or an explicitly requested disjoint-accounting assertion
  containing both IDs. Request assertions with `:partitions ["assertion-id"]`.
  The returned `:basis :attested-disjointness` and `:partitions` preserve exactly
  which assertions were used. A schema-valid assertion is not independent proof
  that a producer accurately described the machine; capture adapters must prove
  their exclusivity rules. Inclusive CPU totals cannot use these assertions.
- Touching positive intervals are disjoint; coincident instants and an instant
  touching an interval need accounting evidence. Disjoint time is sufficient
  only because measurement intervals cover the attributed work.
- `:maximum-observation` reports the largest selected value. For RSS peaks this
  is the largest individual observed peak, **not** concurrent total memory or a
  run-wide peak. It does not need disjointness. Raw counters are refused by both
  operations; counter-to-delta conversion belongs to validated import.

Selections contain 1–256 distinct observations and at most 32 distinct assertion
IDs. Pair checking stops at the requested budget (0–32,640). Each checked pair
examines at most those 32 assertion memberships; assertions have at most 256
members. Preparation builds indexes once, separate from per-call work limits.
The canonical whole result must fit 1–256 KiB, as requested. A byte-limit failure
is a named library exception. Other refusals carry a closed code and at most two
subject IDs, with **no value**: missing observations/assertions, incompatible
semantics, uncertainty, unsupported forms, non-additivity, unproven overlap,
pair-budget exhaustion and arithmetic overflow. There is no silently partial sum.

Counter reset/continuity proofs, uncertainty-aware estimates, broader metric
algebra, automatic accounting partitions, cross-run comparability and regression
decisions remain unimplemented. Environment fingerprints and load evidence are
required before interpreting a numeric difference as a performance regression.

## Comparing task observations

`gate.diff/prepare` consumes two trusted contexts from `gate.query/prepare` and
builds their comparison profiles. `gate.diff/page` then accepts:

```clojure
{:select {:op :tasks :changed-only? true :where {:kind :gate}}
 :budget {:visits 100 :rows 20 :bytes 65536}}
```

`:where` is optional and supports only `:kind` and `:key` equality. Rows are
ordered by kind name and semantic key. Each identity is the pair
`{:kind ... :key ...}`; different kinds never collapse because their keys happen to match.
The producer must make task keys stable and scoped to the task's meaning,
including parameterization/context where needed. The library does not guess
that unrelated tasks or repositories with the same label are comparable.

The comparison scope is explicitly `:task-observations`:

- Each side's profile retains the declared-gate flag, exact observation and
  outcome counts, three fingerprints and up to four example occurrence IDs.
  Gate declarations appear even when the capture contains no invocation. Retry
  attempts and repeated observations retain multiplicity; the comparison does
  not arbitrarily pair repeated invocations across captures.
- The semantic fingerprint covers the multiset of record kind, label, attempt,
  outcome, decision reason and immediate parent's semantic identity/attempt.
  Capture-local IDs and provenance references are excluded from this projection.
- The duration fingerprint covers the multiset of execution attempt and exact
  elapsed duration, with an explicit unfinished marker. Decisions contribute no
  duration. Clock origin and interval placement are excluded. This describes a
  duration distribution, not a matched per-invocation regression estimate.
- An association fingerprint retains each execution's semantic-state/duration
  pairing. Swapping a failed invocation's duration with a successful one's
  changes this fingerprint even when the separate state and duration multisets
  remain identical. It still makes no arbitrary cross-run occurrence pairing.
- All three fingerprints use domain-separated, versioned, sorted member digests.
  This preserves duplicate observations while avoiding an unbounded row for a
  task invoked many times. The full capture digest still covers all raw graph
  data, including fields intentionally excluded from these projections.

Rows say `:unchanged`, `:changed`, `:before-only` or `:after-only`. For matched
identities, `:dimensions` names changed `:semantics` and/or `:durations`, or
`:association` when only their within-capture pairing changed.
An unchanged row asserts equality of those projections only: causal edges,
resource measurements, provenance and scheduling placement are outside this
comparison. One-sided presence does not assert task deletion or successful
selection; every page includes `:before-complete?` and `:after-complete?`.
An expected-but-unobserved gate has zero observations and its declaration,
never a fabricated cache or pass outcome.

Drill either capture using its original query context and the row identity:
`{:op :nodes :where {:kind :gate :key "compile"}}`. Continue into measurements
using the chosen occurrence IDs. Example IDs are a bounded sample, not an
enumeration of the whole task group.

Comparison preparation is separate from page budgets. Each capture can have
at most 256,000 profiles: its 128,000 observed nodes plus 128,000 declared gate
keys. A page performs a sorted merge and counts every visited identity,
including filtered or unchanged ones. It does not materialize a combined
inventory before applying the visit limit. Row/visit/whole-response byte limits
match node drilling. Continuations bind both complete artifact digests,
direction and selection, and must point between complete merged keys. A
byte-stopped key remains at the same position for a larger-budget retry.

Graph-wide dependency/resource/measurement diff, clock/environment/workload
comparability, history and performance-regression verdicts remain outstanding.
Different observed durations alone do not establish a regression on a contended
machine. Profile construction cost, instrumentation cost and browser parity
still require runtime/scale validation.

## Commands and proof status

From the Protogen checkout that contains this module:

```sh
bash tools/uber.sh 'cd tools/gate-graph && clojure -M:test'
bash tools/uber.sh 'cd tools/gate-graph && clojure -M:demo'
```

The demo is entirely synthetic. The test runner requires schemas for every
bound function var in its enrolled JVM source namespaces, including private helpers,
enables Malli instrumentation and refuses an empty suite. Tests include JVM
arbitrary-precision integer and discrete point-set oracles; nested closure and
named graph mutations; canonical round trips; continuation identity; byte and
visit limits; Unicode/escaping; explicit incomplete/PMU states; aggregate overlap,
coverage, provenance, extrema and budget refusals; grouped task comparison,
incomplete captures, retries and continuation merge boundaries. The source-bound
command records the actual test inventory, invocations and assertion counts,
including a cold admission regression before warm-up. Import tests include 80
seeded Malli-generated counters. Broader schema-derived generation remains owed.

Pinned formatting, native clj-kondo and structural gates cover the module.
Splint remains report-only under the repository's existing policy. The normal
`gate-graph-test` target runs the observed suite and an attributed coverage
canary; CI and pre-push invoke it. Remaining integration requirements:

- Keep JVM tests, formatter and kondo green; review Splint and run structural
  quality gates. Preserve module and coverage-canary enrollment.
- Compile/run the shared contracts/arithmetic/codec/query fixtures in CLJS and
  compare bytes/digests/errors with JVM results, including non-BMP strings.
- Extend attributed mutation assessment beyond admission. Its manual Clojure
  campaign detected 16/16 selected faults with named failing assertions, passing
  neighboring controls, fresh JVMs, frozen fingerprints and passing baselines.
  Run from this checkout's root:
  `bash tools/uber.sh 'cd tools/gate-graph && clojure -M:test:campaign ../../.fork-scratch/mutations'`.
  The campaign writes a closed Malli-checked `report.edn` and separate worker
  EDN counters/logs in a unique ignored directory. Crashes/timeouts are invalid,
  never kills. An earlier cold-stack crash prompted the iterative parser repair.
- Live-input and attempt mutation campaigns detected 5/5 and 4/4 selected faults,
  with clean controls and passing full baselines. Run
  `bash tools/uber.sh 'cd tools/gate-graph && clojure -M:test:live-campaign ../../.fork-scratch/mutations'`.
  They cover byte bounds, stale digests, membership changes, required inputs,
  exclusions, forced execution, isolation, pre/post snapshots and dependencies.
- The process campaign detected 6/6 selected faults, including the sequential
  child-retention defect found by real dogfooding. Run
  `bash tools/uber.sh 'cd tools/gate-graph && clojure -M:test:process-campaign ../../.fork-scratch/mutations'`.
- The separate Docker lane passes eleven tests / 97 assertions, including the
  complete 189-test module suite inside the denied-network profile. It proves
  a real cold receipt, cache hit, unrelated-change hit, changed-input miss,
  forced execution, missing-output restoration and execution-policy invalidation.
  Its earlier real
  container mutation campaign detects 5/5 selected faults with clean controls,
  passing full JVM baselines and independent cleanup of the removal mutant.
- Publication/completion campaigns detect 11/11 selected faults with clean
  controls, frozen fingerprints and full 178-test baselines. They distinguish
  missing source observation, copy byte limits, staged/final verification,
  partial installation, mismatched executed/live snapshots, incomplete keys,
  unknown removal, cancellation and unsuccessful output publication.
- Runtime/ownership campaigns detect 7/7 selected faults with full 189-test
  baselines and clean controls. Byte identity, classpath order, unavailable
  evidence, post-witness runtime changes, key binding, JVM mutex exclusion and
  separate-process OS locking are covered. Run
  `bash tools/uber.sh 'cd tools/gate-graph && clojure -M:test:runtime-campaign ../../.fork-scratch/mutations'`.
  Actual reports measured roughly 0.35–0.46 seconds per runtime probe and
  0.38–0.40 seconds per cache hit for the small fixture in this acceptance run.
  This is sample evidence, not a cross-machine performance bound. Safe batch
  amortization remains necessary for cheap gates.
- Extend bounded I/O beyond the v1 journal adapter, import annotations and host
  samples, full query algebra, producer accounting proofs and fold projections.
  Expand the initial self-contained viewer and its browser acceptance suite.
- Complete contained output/cache/coordinator integration and effective dependency
  discovery around the tested execution profiles, then full change selection; join host
  samples with actual execution intervals and prove full-run coverage.
- Validate real scale/overhead, then produce and review each consumer's own
  private EDN/HTML evidence. Keep public fixtures and compiled assets generic.
