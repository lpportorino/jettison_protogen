# Offline browser acceptance

Run it through the one entry point, from the checkout root (host, needs docker):

```sh
make -f lint.mk gate-viewer-acceptance          # or:
tools/gate-graph/browser/run.sh tools/gate-graph/browser/evidence/<new-dir> [--fixtures DIR]
```

`run.sh` builds the public `gate.view.fixtures` reports in the pinned image
(unless `--fixtures` names prebuilt ones), runs `npm ci` from the lockfile, then
runs `acceptance.mjs` in the digest-pinned Playwright image **with the network
removed** — Chromium with rotation, WebKit in `--fixed-viewports` mode. Exit
`0` green, `1` FAIL (an assertion about the viewer did not hold), `2` ERROR (a
launch failure, a fixture build that failed, or a timeout — not a verdict either
way, and a locator timeout can be the viewer's fault when a control was removed
or renamed), `3` CANNOT RUN (no docker or python3, bad arguments, an existing
evidence directory, a prebuilt fixture missing). The run manifest's
`failure.kind` carries the same FAIL/ERROR split.

Two more gates guard the harness itself, both in `gate-viewer.yml`:
`lint_js.sh` runs the lockfile-pinned ESLint (`eslint.config.mjs`, every warning
blocking) over the repository's hand-authored JavaScript, with `lint_js_test.sh`
breaking each config clause alone; and `canary.sh` plants one viewer defect per
mutant — a light mark under 3:1, a lost cursor halo, an edge casing in the
edge's colour, ruler labels off their times, a call to action that no longer
names the gate — into a green run's fixtures and requires each to FAIL naming
its clause (`make -f lint.mk gate-viewer-canary FIXTURES=<run>/fixtures`).

Invoked directly, the harness takes the fixture root (containing `branch`,
`dense`, `signals`, `incomplete`, `malformed`, `leaf`, `open-signals`) and a
**new** evidence directory:

```sh
node acceptance.mjs /absolute/fixture-root /absolute/new-evidence-root chromium
node acceptance.mjs /absolute/fixture-root /absolute/new-webkit-fixed-root webkit --fixed-viewports
```

Use the pinned Playwright version in `package-lock.json` with its corresponding
browser binaries. The harness records the installed Playwright and browser
versions. It copies only each fixture's `index.html` into an otherwise empty
directory, loads it through `file://`, and rejects external request attempts and
page/console errors. Chromium uses emulated offline mode. WebKit rejects even
minimal local HTML in that mode, so WebKit uses explicit request-abort routing
instead (each run's `manifest.json` records the exact engine versions). Both engines reject every non-file request. Running the browser container
with `--network none` provides an additional network boundary.

The branch fixture exercises desktop 1440×900, narrow desktop 1024×768, iPhone 13
portrait and landscape descriptors, and explicit 390×844 and 844×390 CSS
viewports. Other fixtures use desktop and both phone descriptors.
These are emulations, not physical-device or iOS Safari certification.
WebKit-only `--fixed-viewports` keeps both phone orientations as independent
contexts and explicitly records the four omitted active-rotation states. It
addresses a measured mobile-emulation resize failure, where the layout viewport
retains its previous width. A passing fixed-viewport run does not accept WebKit
rotation; Chromium always exercises rotation.

Assertions cover proportional durations, dependency identities and geometry
restoration, both themes' rendered text contrast, 44 CSS-pixel native controls,
horizontal overflow, keyboard Enter/Escape and phone taps, rotation with details
and search active, time-control synchronization, fragment reload, rejected
invalid fragments, hidden failures, reversible fold membership, literal hostile
labels, 32 discoverable core tracks, aligned resource cursors/windows, resource
evidence identities, track removal, unavailable telemetry, incomplete execution,
cache/deselection/blocked decision markers, malformed-data refusal before
viewer initialization, sole-leaf visibility, late telemetry in open captures,
duplicate fold rejection and excessive page clamping. Native control dimensions are checked
separately from duration bars; data geometry is never enlarged for touch.
Activity-candidate checks open the policy and exact supporting IDs, verify the
selected nanosecond window and retain the proposed experiment without claiming
causation. A separate interval-task capture collapses that raw disclosure and
verifies that both coincident task actions fit the viewport. Fold captures
explicitly scroll the timeline heading into view. Expanded canonical-task and resource-query captures cover raw evidence
on desktop and portrait. Decision markers must paint inside the clipped track,
including the final window boundary. Dense dependencies retain original member
IDs while respecting the routed-edge display budget.
Initial overviews retain collapsed timeline controls. The portrait branch checks
that its first true-duration bar is fully visible without scrolling; the harness
opens the timeline-control disclosure before exercising zoom, pan and themes.
That initial first-bar assertion applies only to the synthetic branch fixture.
Archived reports include additional scope and provenance content; on a short
phone viewport, reaching their first lane can require vertical scrolling.

Each screenshot record includes viewport, theme, scroll, fragment, document
extent, dialog state, open disclosures, lane/track counts and `inspected: false`. Resource captures
include actual scrolled viewport views and full-page light-theme views. The
manifest records fixture HTML SHA-256 identities and `completed: false` on a
failed run. Never treat a partial manifest as a passing matrix.

Screenshots are evidence for subsequent human or image-tool inspection. The
harness does not compare approved visual baselines or mark images inspected.
Device descriptors and screenshots cannot establish real hardware behavior.

**Contrast is measured, and recorded as the minimum ratio found.** Each case's
`textContrast` and `markContrast` hold `{checked, min}` per theme: `checked` is a
COUNT of judged elements and `min` the lowest ratio measured. Text — buttons,
paragraphs, headings, labels, summaries, `pre` evidence blocks, ruler ticks,
crumbs, and an empty input's placeholder — must reach 4.5:1 against its nearest
opaque background. Non-text (WCAG 1.4.11) — every solid duration bar, decision
marker and resource sample against its own track AND against the page-colour
casing that separates it from a crossing edge or the cursor; the shared cursor
against its track and against its page-colour halo; every dependency edge, at
its rendered opacity, against its own casing; and the focus-ring colour against
the page — must reach 3:1. An edge with no casing, a missing cursor halo, or a
casing that is not in the layer directly below the edges fails outright.
Patterned fills (`.running`) have no single colour and are counted under
`skipped`. Both
are judged in both themes on each fixture's overview, not inside dialogs,
folds or companion views; an `axe`-style audit and screen-reader semantics are
not run.

**Every capture has a VLM review brief beside it.** For each `<image>.png` the
harness writes `<image>.png.json`: the state's `expect` checklist (what only
looking can confirm — declared per captured state, so an undeclared state is an
ERROR) plus generic legibility checks, the case identity, and a bounded `dom`
summary of the page at capture time (status line, breadcrumbs, the investigate
panel, each lane's label and bar geometry, the open dialog's heading and text,
the focused element). A full-page capture taller than 1600 CSS px is also cut
into `.tile-N.png` slices. `vlm-index.json` lists every image with its sidecar,
so a reviewer is handed a batch rather than a directory to glob.
Review a batch by launching the `viewer-visual-review` agent (`.claude/agents/`),
which runs on Sonnet and carries the review brief; do not hand-roll a reviewer
prompt on a general-purpose agent, which inherits the session's larger model.

Beyond geometry and contrast the harness asserts: the **Longest gate** action
names the planted gate and duration, is the first keyboard stop, and opens that
gate with Enter; a details dialog is modal (the controls behind it are not
hit-testable), an edge button inside it opens the other endpoint, and closing it
returns focus to the task that opened it; **← Pan** and **Pan →** move the
window symmetrically; **Group evidence** names the failing member exactly;
**Open children** inside details opens the folded level and **↑ Parent** returns;
the planted CPU burst is surfaced as a candidate; `attention=` and `exact=`
survive reload from the fragment; and the planted unavailable GPU sample is
counted rather than zero-filled.

Still NOT asserted, because no public fixture reaches it: **Next lanes** /
**Previous lanes** (dense levels fold into at most 24 lanes), the **More
evidence** continuations, the withheld mixed-scope chart, the eight-track and
512-observation messages. `performance.mjs` records timings and sets no budget.
There is no browser back/forward behaviour to test: the viewer writes its anchor
with `history.replaceState` and listens to neither `popstate` nor `hashchange`.

The [recorded synthetic visual review](../docs/viewer-visual-review.md) and
[per-image manifest](../docs/viewer-visual-manifest.json) separate actual image
inspection from automated assertions and identify any reused identical images.

`performance.mjs FIXTURE_ROOT NEW_OUTPUT_ROOT [REPEATS]` separately records
finite offline Chromium load, drill and detail timings for dense and signals
fixtures, with HTML/bundle hashes and sampled DOM counts. See the
[measurement method and raw results](../docs/viewer-performance.md). It leaves
the screenshot acceptance manifests unchanged.
