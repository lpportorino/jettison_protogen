# Integrated viewer browser acceptance

On 2026-10-08 the viewer was checked again after combining its reviewed source
with newer shared API contracts. All 24 cases passed in Chromium and all 24
fixed-viewport cases passed in WebKit, with zero external requests and browser
errors. Actual image inspection found no blocking visual issue in the recorded
states. This record covers the combined synthetic build, independently of the
[original viewer acceptance](viewer-visual-review.md).

The [integrated per-image manifest](viewer-integrated-visual-manifest.json)
contains all seven source HTML identities, both capture-manifest hashes, browser
versions, harness identity, and individual image hashes and review records.
It distinguishes current image openings from verified reuse of earlier actual
inspection. Capturing an image alone never marks it reviewed.

## Exact build and inspection scope

The producer combined isolated authoring checkpoint `b0568963` with viewer source from
`8cab5c99ac152d036c356fd28ded4359a4d229ef`, preserving both API and viewer registry
entries. This identifies the pre-commit integrated build; the producer records
its final integrated commit and broader acceptance separately. The producer
supplied immutable synthetic HTML files, which were copied into an isolated
acceptance directory. Every copied file's embedded JavaScript was independently
hashed and checked against the supplied bundle before running the browser.

| Artifact | SHA-256 |
| --- | --- |
| Embedded JavaScript | `6a26404e001fc5de10ac389b243ff55c0d3b44a120452534a0a64d7594bd2d85` |
| Producer asset manifest | `624e8f7bb04b540b8046d869760ccda81b55187b56306f8219f5bf3749bf2c91` |
| Report source, including CSS | `43dd8fc2e9254978ccfd1deaa6dc34765deed2aec226e6c6ac2fa7a1007b7fb6` |
| Acceptance harness | `77ba33ce47455a00d17008cd13072afa7d9a2bf47359188d27236189af061179` |

Read-only comparison confirmed that the report, viewer, four view namespaces
and synthetic fixture source were byte-identical to the reviewed viewer
implementation. The shared registry additionally retained the newer API entries.
The acceptance run used the unchanged pinned harness from the viewer commit.
It exercised copied single-file reports in a container with networking disabled.
Chromium also used emulated offline mode; WebKit used explicit request rejection
because its emulated offline mode rejects local files.

| Engine | Cases | Images | Review method |
| --- | ---: | ---: | --- |
| Chromium 148.0.7778.96 | 24 | 76 | Eight current PNGs opened; 68 matched previously inspected PNGs exactly by SHA-256. |
| WebKit 26.4 | 24 | 72 | Every current PNG actually opened using `view_image`; no reused inspections. |

Seventy-four Chromium images matched the previous accepted pixels. Six of those
were nevertheless opened again, together with both changed images. The changed
captures showed a row hover outline and a native tap highlight; labels, evidence
and controls remained readable. The current openings cover desktop chronology,
portrait overview, landscape details, dense folds/companion, GPU tracks,
expanded resource evidence and incomplete execution.

Independent WebKit reviewers opened 20 branch, 26 signal and 26 other-fixture
images. They checked both themes, desktop and phone orientations, fold labels
and failure discovery, resource scales and gaps, candidate evidence and
coincident-task actions, unknown/decision states, malformed input, the single
leaf and late telemetry. These images extend WebKit acceptance beyond the
original automated-only record. The visible dismissal controls were reviewed;
dismissal behavior is covered by the separate automated interactions.

Representative current WebKit images:

- [Portrait overview](visual/webkit-branch-iphone-portrait-overview.png)
- [Desktop folded-group companion](visual/webkit-dense-desktop-companion.png)
- [Landscape GPU track](visual/webkit-signals-iphone-landscape-gpu.png)

## Limits and reproduction

The four active WebKit rotation states remain explicitly skipped and unaccepted.
Chromium rotation checks passed. This is browser emulation, not physical iPhone
or iOS Safari certification. Short landscape screens require ordinary page or
dialog scrolling; tall full-page images were scaled by the image tool, with
viewport captures providing closer inspection. These finite checks do not
establish complete accessibility conformance or universal performance bounds.

The producer's combined JVM suites, campaigns, parity and packaged delivery are
separate evidence. Private consumer regeneration and actual-report inspection
remain consumer-owned acceptance, not inferred from these synthetic results.
No private data, screenshots, identities or paths enter this record.

Use the [browser harness](../browser/README.md) with newly generated integrated
fixtures and fresh output directories. The pinned environment is Playwright
1.60.0 in `mcr.microsoft.com/playwright:v1.60.0-noble`, container manifest SHA-256
`9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948`.
Run Chromium normally and WebKit with `--fixed-viewports`; keep the four rotation
skips visible. Open new screenshots or prove byte identity to an actual prior
inspection before recording visual acceptance.
