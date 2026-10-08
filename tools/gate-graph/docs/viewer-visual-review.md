# Synthetic viewer visual review

Reviewed on 2026-10-08 using Playwright 1.60.0 and Chromium 148.0.7778.96.
The final `visual-11` run passed all 24 fixture/device cases and captured 76
images. The [per-image manifest](viewer-visual-manifest.json) binds each image
to its SHA-256, source HTML hash, viewport, state and actual reviewer. Review
completion and the exact inspection method are recorded per image; screenshot
generation alone never counts as inspection. Earlier actual inspections are
reused only when the final PNG has an identical SHA-256.
All 76 images are covered: 16 were opened in the final collection and 60 have
verified identical bytes to earlier actually inspected images. No blocking
visual finding remains within this recorded matrix.

All inputs are public synthetic fixtures. The bundle SHA-256 is
`8552f7957e69bbd9005df0bea61958dffc78275525b99ed93770aa172aadbc31`.
The seven fixtures cover a chronological branch, 3,000 children and 2,964
dependencies, 32 discoverable CPU cores plus host/GPU/device signals, incomplete
execution, malformed input, a single leaf and measurements extending beyond the
last task event. No private report or external service is part of this evidence.

The matrix covers desktop, narrow desktop, portrait/landscape phone descriptors
and two explicit phone viewport sizes. Chromium exercises rotation while task
details and search are active. Checks cover proportional geometry, dependency
identity, reversible folds, whole-graph failures, keyboard/touch activation,
fragment reload, both themes, shared time windows/cursors and absent evidence.
Decision marks are checked for actual painted intersection with their clipped
track, including the final time boundary. See the
[harness instructions](../browser/README.md) for the precise automated scope.

Actual image review includes expanded candidate policy and exact sample IDs,
canonical-task evidence on desktop and portrait, and expanded resource query
evidence on desktop and portrait. Separate candidate-task captures show the
coincident B-check/C-check actions after collapsing long raw evidence. The
candidate list, selected interval and proposed experiment were reviewed as
separate states. Full-page light resource images establish vertical continuity;
viewport captures show readable local labels, controls and shared cursors.

The review led to fixes for clipped decision marks at the final boundary,
cramped landscape fold labels, dense arrows crossing row text, an initially obscured portrait timeline, and
implausible synthetic per-core/pressure values. Final captures show the repaired
surfaces. Capture framing was also corrected to expose actual folded rows and
candidate task actions. Short landscape screens still require normal vertical
scrolling to reach the timeline; long raw evidence uses internal dialog scrolling.
Archived reports add scope and provenance content, so their first lane may also
require scrolling on a short phone viewport. The automated initial first-bar
assertion is scoped to the synthetic branch fixture, not arbitrary archives.
Separate [finite browser measurements](viewer-performance.md) record load,
drill and detail costs without making a general latency guarantee.

Representative images are checked in below. The full raw capture sets remain
local; all final image hashes and inspection records are public in the manifest.

| Surface | Evidence |
| --- | --- |
| Desktop chronology | [Overview](visual/chromium-branch-desktop-overview.png) |
| Initial portrait timeline | [Overview](visual/chromium-branch-iphone-portrait-overview.png) |
| Landscape folding | [Folded rows](visual/chromium-dense-iphone-landscape-folds.png) |
| Candidate evidence | [Portrait detail](visual/chromium-signals-iphone-portrait-candidate-detail.png) |
| Resource evidence | [Portrait dialog](visual/chromium-signals-iphone-portrait-resource-evidence.png) |
| Incomplete capture | [Portrait overview](visual/chromium-incomplete-iphone-portrait-overview.png) |

WebKit 26.4 passed all 24 fixed-viewport cases in `visual-webkit-06`, capturing
72 images. Those images have not received this visual inspection, so this is an
automated result only. Four active-rotation states are explicitly skipped:
mobile resizing left the layout viewport at its former width in the unrestricted
WebKit run. WebKit also rejects local files with emulated offline mode enabled;
its tested fallback aborts every non-file request and runs in a container with
networking disabled. Chromium uses both emulated offline mode and the same
network boundary. Both runs recorded zero external requests and browser errors.

These are browser emulations, not physical iPhone or iOS Safari certification.
Text contrast and control-size assertions are partial accessibility checks;
they do not establish complete screen-reader or accessibility conformance.

To reproduce, build current viewer assets and generate the fixtures from
`tools/gate-graph` using the repository's Clojure environment:

```sh
clojure -M:viewer-build
clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M -m gate.view.fixtures /tmp/gate-view-fixtures
```

Run the [pinned browser harness](../browser/README.md) against that fixture
directory and a new output directory. The original campaign used
`mcr.microsoft.com/playwright:v1.60.0-noble` with `--network none`, an isolated
copy of each single HTML file, and `webkit --fixed-viewports` for the second
engine. Open each new screenshot before issuing a new visual acceptance claim;
the harness deliberately leaves `inspected: false`.
