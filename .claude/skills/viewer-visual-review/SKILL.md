---
name: viewer-visual-review
description: Visual review standard for the offline gate-report viewer's browser captures. Use after tools/gate-graph/browser/run.sh produces evidence whose screenshots changed — resolve the batch with batch.py, review each capture against its `.png.json` sidecar (expect-checklist + DOM summary) and its prior accepted capture, and record per-image verdicts. Owed before any push that changes what the viewer renders (viewer source, its CSS in gate/report.clj, or the harness's captured states).
allowed-tools: Read, Glob, Grep, Bash, Write
---

# Viewer visual review — look at what the assertions cannot

`acceptance.mjs` asserts behaviour, geometry and contrast arithmetic. It cannot
see whether a capture READS correctly: clipped or overlapping text, a mark that
vanishes against its neighbour, a dialog that is cramped, a focus ring in a
nonsensical place. That is this review's subject, and nothing else.

Run it on **Sonnet** through the `viewer-visual-review` agent, one agent per
batch, several batches in parallel. It is a checklist read, not design work.

## 1. Resolve the batch — never glob

```sh
python3 .claude/skills/viewer-visual-review/batch.py CURRENT_RUN PRIOR_RUN OUT_PREFIX [--parts N] [--vanished NAME]...
```

`CURRENT_RUN` must be a GREEN run: a red one stops at its first failure, so the
states after it were never captured, and `batch.py` refuses it (exit 3). A
capture the prior run had and this one lacks is likewise refused unless
`--vanished NAME` names each such capture as removed on purpose — say which and
why in the commit.

`CURRENT_RUN` and `PRIOR_RUN` are evidence directories written by `run.sh`
(each holds `chromium/` and `webkit/`). It emits `OUT_PREFIX-<engine>[-<part>].json`
batches listing only captures that CHANGED against the prior run, each as
`{image, prior, sidecar, tiles}`, and prints how many were unchanged. A capture
inherits the prior review only when its bytes AND its sidecar's `expect` list
are both unchanged; new checklist items were never judged, so a changed `expect`
puts an identical capture back in the batch. A capture with no prior is still
reviewed (`prior: null`).

Chromium rasterises a few border pixels differently run to run (measured: max
channel delta 2/255 on rounded-border anti-aliasing). `batch.py` reports such
captures as PIXEL-EQUIVALENT and leaves them out of the batch; record them that
way, never as byte-identical.

## 2. Review each entry

1. Read the sidecar. `expect` is this capture's checklist (declared per state in
   `acceptance.mjs`, plus generic legibility checks). `dom` says what the page
   held at capture time: status line, breadcrumbs, investigate actions, rows with
   bar geometry, the open dialog's heading and text, the focused element
   `{tag,id,role,text,focusVisible}`, selected edges, scroll, viewport and marks
   in view. `focusVisible: false` is a correctly invisible ring after a mouse
   click or tap, not a missing one.
2. Open the current image with Read, every tile (overlapping 1600 px slices of a
   tall capture), and the prior.
3. Separate INTENDED changes — the caller lists them, and they come from the
   commit under review — from everything else. Confirm intended changes look
   right; treat any other difference as a candidate defect.
4. Judge every `expect` item.

Look hard for: clipped or overlapping text; controls cut off at a SIDE edge;
illegible text or marks; dependency edges or the cursor invisible against bars;
a dialog that does not fit; ruler labels away from their ticks; bars misaligned
with the ruler; a focus ring missing while `focusVisible` is true, or drawn
somewhere nonsensical; any visual claim the DOM summary does not support.
Content continuing below the BOTTOM edge of a viewport capture is not a defect.

## 3. Severity and output

- `blocker` — the capture misleads or hides information a user needs.
- `minor` — readable but wrong: framing, spacing, wording, a cosmetic artefact.
- `uncertain` — you cannot tell from the pixels; say why. Never round it to pass.

Write a JSON array, one object per batch entry, in batch order:

```json
{"image": "<path>", "opened": ["<every path actually opened>"],
 "verdict": "pass", "changeVsPrior": "<one sentence>",
 "findings": [{"expect": "<item or 'other'>", "detail": "<what and where>",
               "severity": "minor"}]}
```

`verdict` is `pass` or `finding`. `opened` lists exactly what you opened.

## 4. What the main session does with it

A visual finding is a LEAD, never a gate verdict: reproducible lanes are the
gates, a vision pass is not. Each finding is dispositioned before the push —
fixed (and, where it can be, turned into an assertion in `acceptance.mjs`), or
named in the commit message as deferred and why. The per-image record of what
was opened and judged goes into
`tools/gate-graph/docs/viewer-upgrade-visual-manifest.json`.
