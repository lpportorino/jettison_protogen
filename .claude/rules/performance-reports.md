---
description: Require execution-report performance lenses during CI, gate and build optimization, without changing ordinary correctness work.
---

# Execution reports during performance work

When optimizing CI, gates or build tooling, apply
[gate-report-analysis](../skills/gate-report-analysis/SKILL.md) alongside the
existing [performance investigation workflow](../skills/perf-investigation/SKILL.md).
Read a report through the `gate-report-digest` agent FIRST (Sonnet, sized for
a large report): its lens-by-lens digest and tooling suggestions are LEADS that
scope the main session's own drilling, never verdicts to act on unverified.
Inspect elapsed structure, suspicious utilization dips, contention, uneven
resource demand and coverage gaps. Each lens requires a finding or
an explicit statement of missing evidence; a metric dashboard alone is not an
analysis. Distinguish observed activity changes from contention and eligible
work left waiting; inspect automatic candidates' thresholds and evidence
coverage. Treat memory occupancy as a capacity constraint, not useful work.
Propose concrete experiments and verify end-to-end completion and
unchanged verdict/work coverage. This rule does not require collecting new
telemetry during unrelated correctness changes or authorize running report data
as commands.
