# Offline execution report viewer

Open `index.html` directly. It embeds its code, style, canonical graph and optional
archive metadata; no server, font service or sibling file is required. The viewer
helps choose an investigation and a controlled optimization experiment. It does
not collect telemetry or certify that an observed coincidence is causal.

## Investigate, then test

Start with **Where to investigate**: inspect the longest recorded gate and any
qualified activity-change intervals. Each candidate opens its exact time window,
coincident tasks, measurement IDs, detector policy and a resource-specific next
experiment. Inspect prerequisites, resource ownership and readiness before
rescheduling. Higher utilization alone is not a successful optimization; compare
end-to-end elapsed time with unchanged workload, verdict and cache coverage.

**Needs attention** searches the whole graph, including failures beneath folded
or currently closed parents. Search matches literal ID, key, label and outcome.
It does not evaluate a query language. Clear the filter or choose **Run overview**
to return. The overview opens the sole root's children when that root has children;
a single leaf remains visible.

The timeline uses chronological run-relative time. A bar's position and width
represent observed start and elapsed duration, not CPU ownership. Its separate
44 CSS pixel label control remains selectable even when the bar is tiny. A
running bar extends to the last known observation and remains visibly unfinished;
its final duration is unknown. Decisions are point marks, never executions.

Solid arrows are observed relationships; dashed arrows are declared ones. Select
a task to inspect exact edge kind and endpoint phase, including edges to other
levels. Ordering, containment and time overlap do not create dependency edges.
No dependency data means no arrows. This viewer does not compute a critical path.

Dense short tasks fold into exact groups. A group retains member IDs, outcome
counts, unfinished/decision counts and occupied intervals, including gaps.
**Group evidence** exposes membership and incident edges; opening the group gives
its companion timeline. **Back**, **Parent** and **Run overview** provide escape.

For B=[100,180] ms and C=[100,240] ms, the envelope and occupied union are both
140 ms, while summed inclusive durations are 220 ms. The sum is neither elapsed
time nor exclusive work. A gap changes the occupied union independently of the
envelope. A fold is a presentation projection, never a new observed task.

## Resource evidence and performance lenses

Up to three available host tracks are selected initially; the chooser can add logical cores,
GPU activity, GPU memory and device I/O. Eight selected tracks are displayed at
once. The ruler, window and shared cursor align with the task lanes. **Inspect
track evidence** works with keyboard and touch, providing bounded canonical
sample pages and continuation controls, including source, scope, method,
accounting, interval and uncertainty. No hover-only detail is required.

| Evidence | Interpretation and next question |
| --- | --- |
| CPU service delta / actual covered wall interval | CPU equivalents: 1 is one logical CPU. It is not host percentage without a capacity denominator. A dip suggests checking ready work, dispatch, waits and quotas. |
| Memory bytes gauge | Sampled footprint/capacity occupancy. A drop does not prove wasted useful work. Inspect pressure, reclaim/faults or allocation evidence. |
| Byte delta / covered interval | Throughput in MiB/s. Bursts become stronger contention evidence when aligned with pressure/queue information. Consider staggering competing work or pairing complementary work. |
| Pressure time delta | Stall time as a percentage of its covered interval. Preserve resource/source scope; this is separate from activity. |
| GPU utilization gauge | Sampled activity under the producer's method. Check feeder work, transfers and device sharing. GPU memory occupancy is a separate quantity. |
| Missing, reset, partial or incompatible observations | Unknown. No zero fill, interpolation, invented rates or clock joins. Mixed task/source/form/accounting series withhold their chart and retain exact evidence. |

Charts use independent labeled vertical scales. They do not sum overlapping
inclusive CPU records or memory peaks. Gauges describe samples; their interval
coverage must come from the producer. Exact raw counters stay available but are
not silently converted to rates. Clock alignment is established by graph
admission; unsupported cross-clock joins are not invented by this presentation.

Activity-change policy `:activity-change-v1` requires 20 contiguous measured
baseline observations and three consecutive values strictly below half or above
twice the baseline median. The median averages the middle two values and is
observation-weighted. A zero baseline, uncertainty, gaps or mixed provenance
suppresses the candidate. Supported quantities are CPU service, bytes read/write,
GPU utilization and memory-current bytes; memory findings are named footprint
changes. Pressure and test counters are not mislabeled as activity. Separated
instantaneous gauges do not meet this policy's contiguous coverage requirement.
The thresholds are a declared heuristic, not a universal statistical standard.

The detector inspects at most 64 series, 512 observations per series and displays
six candidates. These finite prefixes are disclosed; no candidate does not mean
no opportunity. Candidate evidence identifies its 20 baseline and three changed
samples. Eligibility, causal attribution and possible time savings remain
unknown until tested. See the [primary-source rationale](viewer-research.md).

Optimization work in this repository must apply the
[report-analysis skill](../../../.claude/skills/gate-report-analysis/SKILL.md).
Its required lenses cover elapsed structure, suspicious lulls, saturation,
uneven demand, and coverage/correctness. Each lens needs a finding or an explicit
evidence gap. The [synthetic skill validation](skill-validation.md) records its
finite walkthrough; it is not a claim about an autonomous agent's future behavior.

## Bounded agent interfaces

Canonical EDN remains authoritative and lossless. Browser display rounding never
rewrites the report. Natural quantities are decimal strings; shared interval
arithmetic is exact. Geometry subtracts its local time origin before floating
point conversion. Evidence-selected windows preserve exact decimal endpoints;
ordinary zoom uses finite presentation fractions.

Read an archive once through `gate.report-io/read-archive!` with explicit limits,
then retain `gate.view.model/prepare` locally. The immutable prepared map includes
indexes and must not be exposed as a tool response or accepted from a client.
Replace it after any artifact change. Bound an adapter's cache and cumulative
page work; an individual page limit is not a process-memory or elapsed-time cap.

`gate.view.model/page` accepts a closed `gate.view.contract/Request`:

```clojure
{:select {:op :edges :members ["B-check" "C-check"]}
 :budget {:visits 128 :rows 25 :bytes 65536}}
```

Selectors are `:members`, `:edges`, `:neighborhood`, `:search` with `:text`, and
`:correlation` with a half-open `:interval`. Neighborhood returns immediate
endpoint nodes over every recorded edge kind; it is not transitive reachability.
Edge pages retain internal and boundary edges. Correlation returns coincident
measurements, never attribution. For active tasks use the core query's `:window`
selector; for original per-task/resource records use `:measurements`.

Each response binds its artifact, rows, visits, stop reason and `:next` cursor.
Continue the identical selection with `:cursor (:next page)` until `:complete`.
An empty page can require continuation. A byte-stopped page can retain its offset;
increase the byte budget before retrying that cursor. Never loop without a total
page/work limit. Budgets can change; artifact/selection identities cannot.

The page maximum is 128,000 candidate visits, 1,000 rows and 1 MiB including its
response envelope. Every examined candidate costs a visit. Preparation, schema
validation, selection hashing and row encoding are separately bounded work.
Fold membership is 1–256 unique admitted nodes; `fold` returns exact members,
occupied segments and distinct duration summaries. Keep its member-derived ID
beside the artifact ID; it is not a globally unique capture identity.

View failures are `:invalid-view-request`, `:invalid-view-cursor` and
`:missing-view-member`. The detector rejects malformed/oversized input with
`:invalid-opportunity-samples`. Shared admission, arithmetic and instrumentation
have their own documented failure contracts. Do not mistake schema validation
for global graph or provenance validation.

The [deep API review](viewer-api-review.md) inventories entrypoints, trust and
lifecycle boundaries, findings, executable drilling/aggregation examples and
limitations. The view schemas and candidate/failure schemas are additive entries
in `gate.schema/registry`; canonical `:value` now admits empty candidate vectors.
Existing graph/archive versions are unchanged. Older readers are not promised
to accept new view-result shapes. Consumers must explicitly adopt or defer new
API surfaces under their own compatibility policy.

## Delivery and validation

From the repository root, use the pinned toolchain:

```sh
bash tools/uber.sh 'cd tools/gate-graph && clojure -M:viewer-build && clojure -M:test'
bash tools/uber.sh 'bash tools/gate-graph/browser/api-parity.sh'
bash tools/uber.sh 'bash tools/gate-graph/browser/delivery.sh'
```

The viewer manifest binds all browser source bytes and the release bundle.
Source changes require rebuilding the packaged asset; the source/resource JAR
check runs without source directories on the classpath. Publishing new HTML
retains the original canonical graph; never edit observations to fit the UI.
Existing HTML keeps its embedded old viewer until deliberately regenerated.

[Browser acceptance](../browser/README.md) runs the HTML copied alone with network
access refused. It covers desktop, narrow desktop, iPhone 13 portrait/landscape,
full-screen breakpoints, both themes, keyboard/touch, rotation, fragments,
folds/failures, 32 cores/GPU/disk, uncertainty, incomplete states and malformed
input. A capture is not an inspection. The final evidence identifies exactly
which images were opened, by whom, findings, repairs and reruns. Browser emulation
is not a physical iOS Safari test.

Presentation limits are explicit: 24 lanes per page, 128 members per automatic
fold, 120 routed visible edges, eight displayed resource tracks and 512 displayed
observations per track. Truncation is not downsampling; canonical records remain
complete and accessible through bounded pages. Stable IDs retain cross-fold
endpoints even when routing is suppressed. No flame/icicle export or external
trace conversion is included in this viewer change.

CPU equivalents are the primary normalized activity measure when CPU service is
available; they complement elapsed time rather than replacing its axis. They do
not make CPU cost immune to cache/memory/frequency contention. Load average is
not a denominator for task duration. Per-task CPU, measured wait or instructions
must come from records with the corresponding scope and method; absent records
remain unavailable.

PSI is a primary contention signal. Linux exposes CPU, memory and I/O pressure at
host and cgroup scopes. `some` measures time with at least some stalled tasks;
`full` measures simultaneous stalling of all non-idle tasks. System-wide CPU
`full` is undefined and reported as zero. For short execution intervals, acquire
validated differences in cumulative `total` stall microseconds; the 10/60/300-second
averages smooth short spikes. Preserve counter/reset and clock evidence. See the
[Linux PSI interface](https://www.kernel.org/doc/html/latest/accounting/psi.html).

Correlate host and CI-cgroup pressure with CPU equivalents and active gates.
Host PSI includes the CI workload itself: it cannot independently identify a
background culprit. Do not subtract host/cgroup percentages as if scopes were
additive. Low CPU activity with low PSI can still reflect dependency, dispatch,
lock or acquisition gaps; eligibility evidence is required before labeling it
missed parallelism. These are investigation lenses, not automatic attribution.
