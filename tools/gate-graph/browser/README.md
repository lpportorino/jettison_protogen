# Offline browser acceptance

Build the viewer and generate the public `gate.view.fixtures` reports before
running this harness. Pass the directory containing `branch`, `dense`, `signals`,
`incomplete`, `malformed`, `leaf`, and `open-signals`, followed by a **new** evidence directory:

```sh
node acceptance.mjs /absolute/fixture-root /absolute/new-evidence-root chromium
node acceptance.mjs /absolute/fixture-root /absolute/new-webkit-evidence-root webkit
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
Text contrast checks cover opaque rendered text/background colors; they do not
prove focus visibility, chart interpretation, screen-reader semantics or all
accessibility requirements. Record those findings separately after opening the
actual captures. Device descriptors and screenshots cannot establish real
hardware behavior.

Read the manifest's `darkContrast` / `lightContrast` as COUNTS: each is the
number of text elements (`button,p,h1,h2,h3,label,summary,.ruler span,.crumb`
on the initial overview) whose computed ratio met 4.5:1, which the run asserts
for every one of them. Neither is a ratio, neither covers `pre` evidence
blocks, inputs, SVG or non-text marks, and neither is measured inside a dialog,
fold or companion view.

What `acceptance.mjs` does NOT assert, so a green run says nothing about it:
the **Longest gate** button and the activity-burst candidate; dialog-internal
navigation (edge buttons, **Open children**, **Group evidence**, **↑ Parent**,
**← Pan**, **Next lanes** / **Previous lanes** by click); the **More evidence**
continuations; the withheld-chart, eight-track and 512-observation messages;
`attention=` / `exact=` restoration from the URL fragment; focus return on
dialog close or any `activeElement` at all; modal backdrop blocking; Tab order
or an axe-style audit. `performance.mjs` records timings and sets no budget.
There is no browser back/forward behaviour to test: the viewer writes its
anchor with `history.replaceState` and listens to neither `popstate` nor
`hashchange`.

The [recorded synthetic visual review](../docs/viewer-visual-review.md) and
[per-image manifest](../docs/viewer-visual-manifest.json) separate actual image
inspection from automated assertions and identify any reused identical images.

`performance.mjs FIXTURE_ROOT NEW_OUTPUT_ROOT [REPEATS]` separately records
finite offline Chromium load, drill and detail timings for dense and signals
fixtures, with HTML/bundle hashes and sampled DOM counts. See the
[measurement method and raw results](../docs/viewer-performance.md). It leaves
the screenshot acceptance manifests unchanged.
