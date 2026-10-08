# Finite offline browser measurements

On 2026-10-08, [performance.mjs](../browser/performance.mjs) measured three
fresh Chromium contexts per public synthetic report. The [raw JSON](viewer-performance.json)
contains every value, source HTML and embedded bundle SHA-256, workload sizes,
sampled DOM counts, and environment. These HTML hashes match the final visual
review. All six samples completed without external requests or browser errors.

| Operation | Repeat 1 (ms) | Repeat 2 (ms) | Repeat 3 (ms) |
| --- | ---: | ---: | ---: |
| Dense load to ready | 669.8 | 691.0 | 772.2 |
| Dense open 3,000 children into folds | 46.1 | 47.7 | 52.4 |
| Dense open failed fold | 20.7 | 28.8 | 16.6 |
| Dense open child detail | 29.0 | 30.9 | 31.0 |
| Signals load to ready | 406.4 | 428.4 | 437.8 |
| Signals add five resource tracks | 28.1 | 28.1 | 34.7 |
| Signals open resource detail | 29.1 | 27.8 | 29.6 |

The dense report contains 3,005 tasks and 2,964 dependencies in 2,957,794 HTML
bytes. Its sampled maximum was 24 task rows and 309 DOM elements. The signals
report contains five tasks, four dependencies and 2,470 measurements across
38 series, including 32 logical cores, in 1,866,981 HTML bytes. Its sampled
maximum was four task rows, eight resource tracks and 712 DOM elements. Both
files embed the same 453,547-byte JavaScript bundle. The limits apply to the
sampled rendered states, not total canonical data size or transient allocations.

The environment was headless Chromium 148.0.7778.96, Playwright 1.60.0,
Node 24.15.0 and Linux x64 on an AMD Ryzen 9 9950X3D. The container exposed
32 logical CPUs and about 60.5 GiB of host memory. Tests used a 1440×900 viewport
and the pinned Playwright Noble image with `--network none`, plus browser offline
mode and abort routing for every non-file request.

Load time runs from the browser navigation time origin until admission produces
a nonempty timeline, followed by two animation-frame callbacks. Interaction
time covers native click/change dispatch, its synchronous rendering work and
two animation-frame callbacks. Adding five tracks is one timed batch of five
change events. These timings include frame scheduling; they exclude Playwright
actionability waits and do not directly measure compositor presentation. Fresh
contexts share one browser process and host filesystem caches. No cache-flush,
physical phone, WebKit, memory-peak, percentile or universal latency claim is
made from these six samples.

Generate the public fixtures as described in the [visual review](viewer-visual-review.md),
install the pinned browser package with `npm ci` in `tools/gate-graph/browser`,
then run this command from the repository root. Use a new output directory:

```sh
docker run --rm --network none \
  -e GATE_BENCH_CONTAINER_IMAGE=mcr.microsoft.com/playwright:v1.60.0-noble \
  -v "$PWD:/workspace" -v /tmp/gate-view-fixtures:/fixtures:ro \
  -w /workspace/tools/gate-graph/browser --entrypoint node \
  mcr.microsoft.com/playwright:v1.60.0-noble \
  performance.mjs /fixtures /workspace/.fork-scratch/performance-new 3
```

The script writes its own `performance.json` and isolated HTML copies. It does
not rewrite screenshot acceptance manifests or capture additional images.
