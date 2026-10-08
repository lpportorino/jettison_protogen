# Viewer design references

Primary sources verified online on 2026-10-08. These support design decisions,
not a claim that a library was integrated, a benchmark was reproduced, or this
viewer implements every referenced capability. Moving documentation links are
not dependency pins. Three research agents separately reviewed rendering,
resource semantics, and inspection APIs; the implementation author reconciled
the recommendations against the existing portable contracts.

## Chronology, folding and offline rendering

| Reference | Adopted lesson and boundary |
| --- | --- |
| [Perfetto tracks](https://perfetto.dev/docs/instrumentation/track-events) and [UI](https://perfetto.dev/docs/visualization/perfetto-ui) | Keep slices, counters and causal flows distinct. Selection should expose incident edges and fit relevant time. |
| [Perfetto embedding](https://perfetto.dev/docs/visualization/embedding-the-ui) | Its supported self-hosting uses multiple HTTP-served assets; embedding the full engine is not a drop-in single-file solution. |
| [Trace Compass data providers](https://help.eclipse.org/latest/topic/org.eclipse.tracecompass.doc.dev/doc/Data-Providers.html) | Separate hierarchical entries, interval states and typed arrows; synchronize views by time. The desktop Java application is a model reference, not a browser dependency. |
| [speedscope](https://github.com/jlfwong/speedscope) | Minimap, fit-selection and distinct chronological/weighted views are useful interaction precedents. Its offline ZIP is a directory, and its stack model is not a general CI dependency DAG. |
| [D3 zoom](https://d3js.org/d3-zoom) | Optional touch/transform machinery; it does not supply evidence semantics or keyboard controls. |
| [ELK layered layout](https://eclipse.dev/elk/reference/algorithms/org-eclipse-elk-layered.html), [seed](https://eclipse.dev/elk/reference/options/org-eclipse-elk-randomSeed.html), [Dagre](https://github.com/dagrejs/dagre) | General layout engines arrange graph ranks; arbitrary elapsed timestamps require additional constraints. ELK seed zero can use wall time. Use these only if an optional topology view justifies the integration. |

Decision: preserve Hiccup/CLJS and fixed time coordinates, bounded DOM lanes and
SVG connectors. No layout dependency is needed to place observed start/end
instants. This is an architectural judgment, not a comparative bundle benchmark.
Library choices would need a pinned release, license/dependency review and actual
bundle/touch/offline measurements. Current project-level licenses: Perfetto
Apache-2.0 with file exceptions, Trace Compass/ELK EPL-2.0, speedscope/Dagre MIT;
check the exact distributed files before copying or bundling implementation.

## Performance lenses and resource meaning

| Reference | Consequence for investigation |
| --- | --- |
| [USE method](https://www.brendangregg.com/usemethod.html) | Examine activity, saturation and errors independently. Long averages can hide short contention bursts. |
| [Linux proc](https://www.kernel.org/doc/html/latest/filesystems/proc.html) | CPU counters have explicit units; iowait can decrease and is not exact blocked time. Host available memory is different from process RSS. |
| [Cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html) | Preserve hierarchy, quota, effective CPU set and charged-memory scope. Hotplug and changing limits can invalidate comparisons. |
| [Linux PSI](https://docs.kernel.org/accounting/psi.html) | Stall-time signals differ from utilization. System-wide CPU full-pressure is undefined, even when a compatibility field reads zero. |
| [Kernel I/O accounting](https://docs.kernel.org/admin-guide/iostats.html), [sysstat iostat](https://github.com/sysstat/sysstat/blob/master/man/iostat.in) | Throughput, active time, queueing and device layers differ. Disk percentage busy is not a universal saturation measure for SSD/RAID. |
| [NVIDIA SMI](https://docs.nvidia.com/deploy/nvidia-smi/index.html) | GPU utilization is activity during the source sampling period. Memory utilization measures memory-access activity, not VRAM capacity. Sampling and unsupported-device states matter. |
| [Perfetto clock synchronization](https://perfetto.dev/docs/concepts/clock-sync), [Linux clocks](https://www.man7.org/linux/man-pages/man3/clock_gettime.3.html) | Join domains through explicit evidence; monotonic clocks and wall clocks have different suspend/jump behavior. Mapping alone does not quantify uncertainty. |
| [Lamport causal order](https://www.microsoft.com/en-us/research/publication/time-clocks-ordering-events-distributed-system/) | Timestamp ordering cannot replace recorded causal relationships. |
| [NIST outlier labeling](https://itl.nist.gov/div898/handbook/eda/section3/eda35h.htm) | Statistical labels identify investigation candidates, not causes. The viewer's simple ratio rule is a different declared heuristic, not a significance test. |
| [Tetris scheduling research](https://utns.cs.utexas.edu/assets/papers/tetris.pdf), [Borg](https://research.google/pubs/large-scale-cluster-management-at-google-with-borg/) | Complementary resource profiles can inform feasible placement. Dependencies, admission, isolation and limits must still be respected. Their workload results predict no CI speedup here. |

An activity dip is suspicious only in context. A useful next step is to inspect
eligible queued work, dependencies, exclusivity and other resources in the same
interval. More allocated RAM is not an optimization objective. Memory bandwidth,
capacity and pressure need separate observations. Rescheduling suggestions remain
experiments until comparable interleaved runs preserve coverage and improve
end-to-end completion.

Cross-language implementation references include [Rust sysinfo](https://docs.rs/sysinfo/latest/sysinfo/struct.System.html),
[Go procfs](https://github.com/prometheus/procfs), [Go gopsutil](https://github.com/shirou/gopsutil/blob/master/process/process.go),
[Python psutil](https://psutil.readthedocs.io/stable/index.html), and
[Rust/Python tsdownsample](https://github.com/predict-idlab/tsdownsample).
Their baseline-refresh, counter and first-sample contracts must survive any
wrapper. Native collectors belong upstream of canonical admission. M4/minmax
source-index selection is a possible future rendering optimization; extrema and
gap preservation need independent tests, and rendered subsets must never become
analytical totals. This viewer currently discloses truncation rather than
claiming an implemented downsampler.

## Bounded APIs and portable verification

| Reference | Adopted lesson and boundary |
| --- | --- |
| [Google AIP-158](https://google.aip.dev/158), [Relay connections](https://relay.dev/graphql/connections.htm) | Stable ordering and continuations; short/empty pages can be nonterminal. Artifact binding and explicit scan/byte limits are additional local requirements. |
| [Rust petgraph DFS](https://docs.rs/petgraph/latest/petgraph/visit/struct.Dfs.html), [Go Gonum traversal](https://pkg.go.dev/gonum.org/v1/gonum/graph/traverse) | Explicit traversal state is useful, but candidate-edge work and retained frontier need independent budgets; a traversal iterator alone is not a bounded API. |
| [Guava immutable graphs](https://guava.dev/releases/snapshot-jre/api/docs/com/google/common/graph/ImmutableGraph.html), [NetworkX subgraphs](https://networkx.org/documentation/stable/reference/classes/generated/networkx.Graph.subgraph.html) | Immutable structure and shared mutable attributes are different guarantees. Keep the admitted artifact deeply immutable. |
| [Perfetto span joins](https://perfetto.dev/docs/analysis/perfetto-sql-getting-started) | Same-partition overlaps can invalidate span-join results. Generic overlapping CI intervals need union/intersection semantics, not naive sums or inappropriate joins. |
| [DuckDB memory configuration](https://duckdb.org/docs/current/configuration/pragmas#resource-management), [DataFusion runtime](https://datafusion.apache.org/) | SQL row limits and memory settings do not establish whole-process or response budgets. Native engines add lifetime, distribution and browser-parity costs. |
| [Malli](https://raw.githubusercontent.com/metosin/malli/master/README.md), [test.check](https://github.com/clojure/test.check), [CLJS differences](https://clojurescript.org/about/differences) | Use closed schemas and explicit validation, semantic properties and independent oracles. Keep exact time/count transport portable; saved parity fixtures complement seeded generation. |

Decision: implement the small inspection algebra in CLJC and reuse the existing
canonical encoder, graph admission and query preparation. Keep native analytics
engines optional behind the same contracts if measured scale later justifies them.

## Interaction and visual acceptance

[WAI tree/disclosure guidance](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/),
[44-pixel targets](https://www.w3.org/WAI/WCAG21/Techniques/css/C44), and
[dragging alternatives](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html)
inform native controls, visible focus, explicit back/reset and pointer alternatives.
The project chooses 44 CSS pixels for controls without enlarging duration geometry.

[Playwright emulation](https://playwright.dev/docs/emulation),
[visual comparisons](https://playwright.dev/docs/test-snapshots), and
[accessibility testing](https://playwright.dev/docs/accessibility-testing)
support a pinned browser matrix, settled captures and behavioral assertions.
Device emulation is not physical iOS Safari. Captured and actually inspected
images have separate states; a green DOM check does not establish visual quality.

The report-analysis skill follows the standard project skill format documented
by [Claude Code](https://code.claude.com/docs/en/skills). It remains available for
automatic selection and explicit invocation; its performance mandate is scoped
to optimization investigations.
