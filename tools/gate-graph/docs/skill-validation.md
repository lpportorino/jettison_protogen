# Report-analysis skill validation

Reviewed on 2026-10-08 against the requirement to apply actionable performance
lenses during optimization. This is a finite synthetic walkthrough of the
[analysis skill](../../../.claude/skills/gate-report-analysis/SKILL.md) and
[performance rule](../../../.claude/rules/performance-reports.md), not a measured
optimization or a visual acceptance test. No consumer report, path, label or
command was used.

## Scenario and evidence

Request: explain an apparent lull in a complete synthetic CI run and propose
one controlled scheduling experiment without reducing work or verdict coverage.

Start with `gate.fixtures/example`: two passing gates, `compile` followed by
`check`, connected by an observed prerequisite edge. Extend the root/run to 23
seconds, place `compile` at [0,10) seconds and `check` at [10,23) seconds. Retain
the synthetic clock and source. Add these 23 one-second observations per series
on the synthetic worker, with `:node nil`, `:status :measured`, `:reason nil`,
`:method :cgroup` and `:accounting :inclusive`:

| Quantity and form | Observations 0–19 | Observations 20–22 |
| --- | --- | --- |
| `:cpu-user-ns`, `:delta` | 8,000,000,000 ns per interval | 1,000,000,000 ns per interval |
| `:bytes-read`, `:delta` | 1,000,000 bytes per interval | 100,000,000 bytes per interval |
| `:memory-current-bytes`, `:gauge` | 8,000,000,000 bytes | 2,000,000,000 bytes |

IDs are `<quantity-name>-<observation-index>` and all timestamps/values use
canonical decimal strings. Gauge values are observations associated with their
recorded intervals, not proof that the footprint stayed constant throughout.
There is no recorded CPU capacity, ready queue, I/O pressure or memory bandwidth.

## Executed checks

The synthetic graph was admitted by `gate.view.model/prepare`. A correlation
request for [20,23) seconds used the following selector and per-page budgets:

```clojure
{:select {:op :correlation
          :interval {:start-ns "20000000000" :end-ns "23000000000"}}
 :budget {:visits 10 :rows 3 :bytes 8192}}
```

The first request omitted `:cursor`; subsequent requests used the previous
`:next`. An outer limit of 20 page requests made the exercise finite. Completion
took **9 pages** and returned exactly the 9 observations with indices 20–22
across the three series. Every returned source ID remained available; no
prepared index was returned as an analysis result.

Direct `gate.view.opportunity/detect` calls on each 23-observation series returned
exactly one candidate: `:activity-dip`, `:activity-burst` and `:footprint-drop`,
respectively, all for [20,23) seconds. Their baseline and observation IDs contain
20 and 3 members. The declared `:activity-change-v1` policy compares three
consecutive values with half/twice the preceding 20-observation median; it is
not a statistical significance test or a capacity estimate.

Negative and boundary probes were executed:

- Changing CPU baseline observation 19 to `:partial` returned no candidate.
- Converting memory intervals to distinct instantaneous points returned no
  candidate. The current rule requires adjacent intervals to touch. Ordinary
  periodic point gauges are therefore unsupported for automatic candidates;
  this result must not be reported as a steady footprint.
- A 513-observation direct call was rejected with
  `:invalid-opportunity-samples`. This validates the runtime limit independently
  of the viewer's own prefix selection.

The skill-creator `quick_validate.py` check passed. It validates skill structure,
not the performance conclusions. Local Markdown links were checked separately.

## Applying every lens

| Lens | Finding or explicit gap | Action |
| --- | --- | --- |
| Elapsed structure | `check` spans 13 seconds, `compile` 10; the observed prerequisite forbids overlapping this pair. The root envelope and occupied union are both 23 seconds; summing the parent and children would double-count elapsed work. | Profile the slow portion of `check`; do not propose moving it before compilation completes. |
| Suspicious lulls | CPU user service falls from 8 to 1 CPU equivalents over [20,23). Host percentage, total user+system service and eligible queued work are unknown. | Inspect readiness and the phase's wait/dispatch evidence; call this an activity dip, not wasted capacity. |
| Saturation and contention | Read throughput rises while CPU service falls. No I/O pressure, queue latency or resource-limit evidence was supplied. | Acquire the missing waiting/limit signal before asserting an I/O bottleneck. |
| Uneven resource demand | The interval has a disk burst and memory-footprint drop. Occupancy says nothing about memory bandwidth or productive work. | If independent eligible work is established, compare overlapping a CPU-heavy, light-I/O task with this phase after checking memory and exclusive claims. |
| Coverage and correctness | The run and bounded selection are complete, but saturation, readiness and capacity are absent. Both gates pass; no cached decision substitutes for execution. | Preserve these work/verdict checks in the control and treatment, and collect the missing evidence. |

Proposed experiment: after eligibility and ownership are established, compare
the existing schedule with one additional eligible CPU-heavy, light-I/O task
overlapping the read-heavy interval. Keep workload, cache stratum and environment
comparable and interleave control/treatment runs. Compare full completion time,
throughput, spread, pressure and unchanged verdict/work coverage. Without those
new runs the verdict is **inconclusive**, with no speedup or recoverable-time
estimate. The existing two-gate fixture alone offers no legal task to move.

## Review corrections and limits

The skill now explicitly requires readiness before calling a lull possible
missed parallelism, scrutiny of candidate thresholds and evidence sufficiency,
and concrete complementary/staggered scheduling experiments. Memory occupancy
remains a capacity constraint, and clock mappings must retain uncertainty.
The rule requires a finding or evidence gap for every lens.

The detector was reviewed read-only. Its point-gauge limitation was reported to
the implementation owner. The direct-call sample bound initially depended only
on the schema annotation; the owner added runtime validation, and the boundary
probe above verified the resulting rejection. This walkthrough does not claim
that the viewer implements time-weighted anomaly baselines, phase matching,
hysteresis, ready-queue inference or automatic schedule simulation.
