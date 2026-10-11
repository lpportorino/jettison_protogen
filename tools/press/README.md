# press — print self-contained HTML to PDF, and check what was printed

The browser half of the document press. The data half (`press.deck` and
`press.doc`, Clojure, `tools/press/src`) builds ONE self-contained HTML file
from data and proves it self-contained STATICALLY; this half proves what a
static scan cannot see — how the page RENDERS — and hands a visual reviewer
its per-slide or per-page PNGs and contact sheet.

A consumer supplies its documents, figures and house style (`PRESS_HOUSE`,
§ "Figures"). Reference fixtures use invented library scenarios and geometric
defect plants. Their quantities illustrate layout and are not measurements.

```
make -f press.mk image                       # once per Dockerfile or pins change
tools/press/bin/press qa deck.html out/   # every slide: PNG + contact sheet + report.{json,md}
tools/press/bin/press pdf page.html out/  # a PAGED document: A4 PDF + one PNG per page + the layout pass
tools/press/bin/press pdfcheck old.pdf --margins 22,21,24,21   # an ARCHIVED PDF: the reliable subset
tools/press/bin/press fig solve fig.json out/   # FIGURES: candidate layouts + a numbered sheet
tools/press/bin/press chart c.fig.json c.pick.json c.svg      # CHARTS from data: bar, stacked, timeline
tools/press/bin/press selftest            # the both-directions proof (= make -f press.mk selftest)
```

`pdf` is the other half of the same browser: `qa` shoots a SLIDE deck and runs
the detectors registered in `press/deck_qa.py`; `pdf` prints a paged DOCUMENT (a report, a guide) to A4 through the same Chromium with print CSS and
backgrounds on, then rasterises **the finished PDF** with `pdftoppm` into
`page-NN.png`. The raster is from the PDF and not from the DOM on purpose: the
eyeball has to see the browser's own pagination — every `break-inside: avoid` it
honoured or ignored, every figure that landed across a boundary — not the page
the author hoped for. The directory then holds exactly the PDF's pages: every
`page-<digits>.png` an earlier print left there is removed before the print
(other files are kept), and an image count that differs from the PDF's own page
count fails it. It exits 1 on a blank page, a dead link, an element wider
than the page, a blocking layout finding (§ "The paged layout pass") or any
external request the page attempted, and writes `pdf_report.json`.

From Clojure, `(press.deck/qa html out)` returns exactly that host command
as data — a JVM that cannot start containers hands the command to whoever can —
and `(press.deck/qa-report out)` reads the report back into a verdict.

## Detectors

| id | question | mechanism |
|---|---|---|
| D1 overflow | does the content fit the slide | scroll extents + the innermost offending element |
| D2 text overlap | do two text runs collide | GLYPH rects (`Range.getClientRects`), not element boxes |
| D3 truncation | did the browser delete text | ellipsis / clipped-line detection |
| D4 occlusion | is something painted over text | `elementFromPoint` hit test at sample points |
| D5 small text | readable at presentation distance | computed font size against a floor |
| D6 font fallback | did the inlined @font-face apply | `document.fonts` + rendered family |
| D7 images | decode failures, upscaling, duplicate bytes | `naturalWidth`, scale, file-level payload hashes |
| D8 video | playable, broken, or codec-unsupported HERE | codec read from the container bytes, then `canPlayType`, then frames decoded |
| D9 contrast | WCAG ratio over the ACTUAL background | slide rendered twice, glyph ink forced transparent the second time |
| D10 external | did the deck reach outside itself | static scan + route interception + an offline reload |
| D11 console | errors, uncaught exceptions, CSP | page console |
| D12 offscreen | an element entirely outside the viewport | bounding rects |

## What it deliberately does not do

Anything semantic — whether a figure supports its caption, whether a chart is
right, whether the deck is well composed. A visual reviewer judges these
questions from the generated PNGs. Geometric checks and visual review supply
different evidence; a clean report does not establish that the document is useful.

## Traps

- This Chromium has NO H.264. D8 reports an H.264 clip as
  `codec-unsupported-by-checker`, never as broken; a slide whose only content
  is H.264 renders BLANK here, so its PNG is worthless for review. Ship AV1 or
  VP9.
- A deliberately tall slide (an appendix) is an overflow error by the rule.
  Mark it `data-qa-scroll` in the deck (`press.deck`'s `:scroll? true`) or pass
  `--scroll-slides N`.
- D7 counts duplicate payloads in the FILE, not in the DOM: a deck that inlines
  an asset once and re-points its other uses at load shows the same `src` on
  several elements while the bytes exist once.

## Figures — a description in, a checked drawing out

A document figure (boxes with text, the groups they sit in, buses, arrows) is written as a
DESCRIPTION: what is in it and how the parts relate — never where anything goes. `press fig`
turns that into drawings:

1. **solve** — measure every text, let a constraint solver place the boxes, route the edges,
   check every result, and lay the survivors out on one numbered contact sheet.
2. **pick** — a person (or a vision model) looks at the sheet and names a tile. The pick
   record stores the finished geometry, not a recipe for it.
3. **rebuild** — redraw the picked figure from that record, byte for byte, with no solver.
4. **check** — the figure checker (`press/fig/check.py`), also usable on any SVG.

```
press fig solve   <fig.json> <out-dir> [--n 8] [--cap-s 3] [--total-s 60]
               -> out-dir/cand-NN.svg + cand-NN.geom.json, sheet.png, manifest.json
press fig pick    <fig.json> <out-dir> <tile> <pick.json> <out.svg>
press fig rebuild <fig.json> <pick.json> <out.svg>
press fig check   <file.svg> [--json report.json]
press fig shoot   <file.svg> <out.png>      # one SVG at print width, 3x, for looking at it
```

Every container `bin/press` starts runs with no network (`--network none`) and a private `/dev/shm` of
`$PRESS_SHM` (default 1g; never the host's — Chromium keeps renderer frames and print buffers there, and
docker's 64 MB default crashes a renderer on a large page; the tmpfs is allocated only as used).
Input files are mounted read-only, only the directories written into are writable. A path may hold
a colon or a space; a path with a comma is refused, and so is a SYMLINK as an input or an output
(it would be resolved inside the container, to whatever that path names there).

Each prints ONE JSON object — a refusal too, as `{"status", "message"}` — and paths in it are
host paths. Bad input never produces a traceback. One exit-code table for every command (the
same in `press/fig/cli.py`, and `bin/press` follows it):

| exit | meaning |
|---|---|
| 0 | done |
| 1 | no candidate passed every hard check, or the solve ran out of time (`status`: `no-candidate-passed`, `time-limit`, `no-answer`); `check` and `pick`: the checker found defects |
| 2 | `pick` / `rebuild`: the description changed since the solve or the pick (problem hash differs) |
| 3 | `rebuild`: the pick record does not match — its hash, its ids, texts or looks differ from the description, a box lies off the canvas, or the record is malformed |
| 4 | `solve`: unsatisfiable — `explain.json` names the smallest set of rules that cannot hold together; a box wider than its room is named before any solving |
| 5 | `solve`: over the page budget, always — `budget.json` says what the figure needs and proposes a split |
| 6 | invalid input: the description, an argument, a missing file, a nonexistent tile |
| 7 | a defect in press itself (the two solver encodings disagree, an invalid model, the placer and its filter disagree) — never the description's fault |

**What `rebuild` proves, and what it does not.** It needs no solver, router, text shaper or
browser. It refuses a record whose boxes, buses and edges are not exactly the description's
(the same ids, and per box the same look and the same `(style, text)` rows in order), a box
off the house canvas, a malformed record, and bytes that differ from the recorded hash. It
does NOT re-run the checker, and it cannot tell that a box was moved within the canvas with
its hash recomputed: the pick record is trusted for geometry. `pick` is where the checker
gates the figure.

### Units and the house

Figure units are points on paper. Figures print at the house column width and
are never scaled to fit. The canvas size, page budget, text floor, text styles,
box looks and colours come from the house file that both halves read.
`press/house.json` is the default; inspect it for the current values. A consumer with its own style exports `PRESS_HOUSE=<its house.json>`: the launcher
mounts that file read-only for the figure and chart commands, and `press.doc` is pointed at the
same file through `press.config`. A house must carry every key `press/fig/house.py` requires.

### The description

Canonical JSON (keys sorted by code point, no whitespace, UTF-8 unescaped, one trailing
newline; integers within the bounds in `press/fig/desc.py`). press refuses a file that is not canonical, so the
problem hash is simply the sha256 of the file. The file is named `<id>.fig.json`. Every id —
figure, node, group, bus, edge — matches `[A-Za-z0-9][A-Za-z0-9_-]*`. press.doc writes the file
from `figs/<id>.edn`. An unknown key anywhere is an error.

| key | what it holds |
|---|---|
| `id`, `kind` | the figure's name; `"diagram"` (a `"chart"` is drawn by `press chart`, § Charts) |
| `nodes` | boxes: `id`, `look` (a house box look), and EITHER `rows` — `[[style, text], ...]` stacked lines — OR `cells` + `column-set` — one line of table cells whose columns line up with every other node of the same set. Optional `pad`, `align` (`left`/`center`) |
| `groups` | rooms: `id`, `look`, `children` (ids), optional `title` rows, `align`, `layout` (`stack`: the children top to bottom in order, left-aligned with the house gap; `row`: left to right, top-aligned; `free`, the default), `collapsible` |
| `buses` | `id`, `orient` (`h`/`v`), `taps` (ids, or `{node, look}`), optional `label` rows, `look`, `span` (`taps`: the spine runs between its taps; `canvas`: across its whole room). `mode` is `spine`, the only one built |
| `edges` | `from`, `to`, `kind` — `arrow` (head at the end), `tap` (no head), `skip` (dashed; skip edges leaving one box from one side share one trunk) — optional `id` (default `<from>-<to>`), `from-side`/`to-side` (`n s e w`; without them the router takes the two sides that face each other), `look`, `arrow` (`end`/`both`/`none`) |
| `constraints` | a list of `[name, args...]`, below |
| `soft` | objective weights `{"height": 10, "edges": 1}` |
| `view` | draw the same description at another depth: `collapse` (group ids), `depth` (collapse every collapsible group nested deeper), `detail` (one group alone), `refs` (the text naming each linked figure) |

Constraints (gaps in units):

| name | meaning |
|---|---|
| `["order-x", [ids], gap]` | left to right, at least `gap` apart (a vertical bus may be in the list) |
| `["order-y", [ids], min, max?]` | top to bottom, gaps between `min` and `max` (a horizontal bus may be in it) |
| `["same-x", [ids]]` / `["same-y", [ids]]` | equal left edges / equal top edges |
| `["equal-width", [ids]]` / `["equal-height", [ids]]` | the boxes stretch to one size |
| `["span-x", id]` | full width of its room (the canvas at the top) |
| `["fill-x", [ids], gap]` | a row that fills the width with equal boxes and equal gaps of at least `gap` |
| `["hang", id, bus, min, max, side]` | the box's near edge within min..max of the bus; side `free`, `above`, `below`, `left`, `right` |
| `["follow", a, b, bus, gap]` | `a` sits on the far side of `b` from the bus, `gap` away, centred on it |
| `["beside", a, b, gap]` | `a` directly left or right of `b`, exactly `gap` apart |
| `["within-y", a, b]` | `a`'s vertical extent inside `b`'s |
| `["span-y", g, top, bot]` | `g` runs from `top`'s top to `bot`'s bottom |
| `["same-shape", [ids]]` | groups take the same arrangement |
| `["near", id, [edge ids], side, gap]` | a note beside those edges' route (beside their corridor for a loop edge) |

The placer keeps every box within its room, separates boxes, and keeps each
box wholly on one side of a bus spine. It reserves straight runs for arrowheads
and taps, routing channels for edges without named sides, and a corridor for
skip edges. `press/fig/model.py` defines these constraints and their dimensions.

Worked descriptions live in `fixtures/figs/*.fig.json`. `library-map` groups
reading-room activities; `book-cart` connects baskets and shelves through a cart
route; `repair-flow` shows book-care tasks and optional paths. `vocab` exercises
additional constraints and a collapsed group. `reading-tables` covers aligned
cell columns, equal table heights, row filling and positions relative to an aisle.
These examples were invented for
the public fixture corpus.

### How a figure is solved

- **Text** is measured with HarfBuzz on the Inter font files Chromium itself uses, with the
  `calt` feature off (as the SVG asks): the measurement and drawing use matching shaping settings. Every box is sized from its text, rounded up to whole units.
- **Placement** is OR-Tools CP-SAT. Groups go first, innermost out: each group is solved as a
  small figure of its own and offers arrangements (shapes) that differ in topology; the
  level above places the group as one box that may take any of them. A rule may name any
  element, at any depth: it is written at the deepest level holding everything it names.
  Heights are bounded by what a level could ever need (every box stacked, every gap at its
  largest), never by the page budget, so "over budget" (5) and "unsatisfiable" (4) never swap.
- **Deterministic.** One worker, a fixed seed, and CP-SAT's deterministic time (work units,
  roughly seconds on one desktop core) for every limit: `--cap-s` per round, `--total-s` for
  the whole solve. The same description on the same image writes the same manifest and the
  same candidate files. Running out of the total is a named failure (`time-limit`, exit 1),
  saying where it ran out.
- **Diverse candidates.** Each round minimises height, then edge length. The next round must
  differ VISIBLY from every candidate kept so far: a substantial area of leaf
  boxes must move by the distance and size fractions in `press/fig/place.py`. The placer encodes exactly
  the measure the manifest reports (`moved_vs_kept`), so a candidate is never dropped after the
  fact for being "the same". A candidate the checker rejects asks nothing of later rounds: that
  exact layout is excluded, and a second failure of the same arrangement excludes the
  arrangement. When the sheet has fewer than `--n` tiles, `manifest.json` says why in
  `short_because` (search exhausted, a time limit, or the round limit,
  with how many candidates the checker rejected). A tightly described figure honestly has few
  layouts; a loosely described one gets visibly different but often worse ones (the score on
  each tile says which).
- **Routing** is libavoid (Adaptagrams, WebAssembly) plus house rules: facing sides for an edge
  that names none (the widest gap whose exits are clear), aligned ports (an edge between two
  overlapping boxes drops straight), arrow room (pushing a bend out, never an end), bundling of
  skip edges into one trunk, and bus spines whose taps decide their extent.
- **Checking**: every candidate goes through the checker as it is found; a pick is checked again.
- **Unsatisfiable**: the level that failed is re-encoded by the same rule writer for Z3, every
  rule behind its own literal; Z3's minimised unsat core is the explanation, in the
  description's terms (`["order-x", ["m0", ...], 9]` and `m4 must fit in the 476-unit width`).
  If Z3 finds the level satisfiable, the two encodings disagree: exit 7, a press defect.
- **Page budget**: a figure taller than the house budget is refused with what it needs, never shrunk.
  When groups are `collapsible`, `budget.json` proposes a linked figure set — an overview with
  groups collapsed plus one detail figure per collapsed group — writes each as
  `split/<id>.fig.json` (the original description plus a `view`) and shows each one fits. No
  edge disappears: in a detail figure an edge leaving the group ends on a small marker naming
  the box at its other end and the figure that draws it; edges a collapse merges or swallows
  are listed (`edges_merged`, `edges_dropped`, `edges_stubbed`). The document adopts the parts;
  press does not add figures on its own. A marker names the figure that draws the other end when
  that end sits in a group with its own part; otherwise it names the figure this detail is
  part of.

### The checker

| group | checks |
|---|---|
| text | `min-pt`, `canvas-overflow`, `text-outside`, `text-overlap`, `crowded`, `text-straddle`, `text-clipped` |
| edges | `clearance`, `shared-path`, `edge-in-box`, `edge-in-text`, `crossing`, `bends>3`, `unbundled`, `edge-outside`, `unrouted`, `room-escape` |
| arrowheads | `arrow-short`, `arrow-collide`, `arrow-buried`, `arrow-hidden`, `arrow-outside` |
| contrast | `text-low-contrast`: a text over a filled box or mark (the last one painted under its centre) under 4.5:1 WCAG contrast against that fill. The house text colours meet it on every house fill: teal and amber TEXT is drawn in their ink variants (`TEALINK`, `AMBERINK`; `house.json` `text-ink`) |
| references | `external`: an `href` that is not a `#fragment` or `data:`, a `url(...)` that is not `url(#...)`, an `@import`, or any request the page attempted (every request is intercepted and refused) |

This table describes the current check families. `CHECKS` in
`press/fig/check.py` is the authoritative registry. Geometry comes from Chromium at print scale.

### Traps

- OR-Tools 9.12.4544 `AddMinEquality` is wrong with an argument that carries a constant
  (`min(y + 128)` with `y >= 0` came back INFEASIBLE). The model writes min and max with its
  own selector literals.
- libavoid falls back SILENTLY to a straight centre-to-centre line when its shape buffer is
  more than half the gap between two shapes, or when a side's exit is blocked within that
  buffer. The router defines its buffer in `press/fig/route.mjs` and picks sides whose exits are clear; the checker
  catches what is left (`edge-in-box`, `edge-in-text`).

### Pins

The image is tagged by the sha256 of the Dockerfile AND the lock files under `pins/`: node by
image digest, libavoid-js through `npm ci` from `pins/package-lock.json` (integrity hashes),
every added Python package from `pins/requirements.txt` with `pip --require-hashes --no-deps`
(one wheel per package, x86_64, CPython 3.10).

### The selftest

`press selftest` runs `fixtures/fig_selftest.py` inside the image (no network) after the deck
fixtures, then `fixtures/fig_launcher_selftest.sh` through `bin/press` itself. Between them:
the reference figures, `vocab` and a two-box figure with a side-less edge solve, pick and check
clean; each registered check fires on a defect planted into a good figure
(`fixtures/fig_plants.py`) and every base scores zero; rebuild is byte-identical twice, needs no
solver, and refuses a changed description (2), a tampered, a consistently re-hashed and a
malformed record (3); a row that cannot fit and a box wider than its room exit 4 named; an oversized column of
stacked rows exits 5 (not 4); collapsible groups get a split in which every edge survives; two
solves of `book-cart` and of a loose figure are byte-identical; hostile ids, ids that are not the file
stem, out-of-range integers, lone surrogates, bad arguments, missing files and nonexistent
tiles exit 6 without a traceback; a route's start never moves; a tiny `--total-s` fails by
name; the launcher handles a directory named `a:b c` and every subcommand; a trailing-newline
id, a view collapsing one group twice and list-valued view entries exit 6, and a mutation sweep
over the reference descriptions (every leaf replaced by each JSON type) never throws past the
refusal; the checker scores paths with no points as nothing and never crashes on them.

Then the paged layout pass (`fixtures/paged_selftest.py`, then `paged_launcher_selftest.sh`): every
planted page and every twin gives exactly the pinned findings and exit code, a copy with one severity
demoted turns that red, the report has one findings list and `html_sha256` is the page's hash,
`--allow-layout` waves through only what it names, outside requests are aborted and named, pages
with nothing to print and a file that changes during the print exit 6, an untagged print, renumbered
ids and a mismatched pairing are refused by the join guard, the two tightened detectors go red with
the tightening undone, pdfcheck is pinned the same way and refuses corrupt and text-less PDFs, and the
launcher refuses symlinks and handles `a:b c`. Then
the charts (`fixtures/chart_selftest.py`, then `chart_launcher_selftest.sh`): the reference charts
draw clean twice with identical bytes, match their pinned golden hashes, keep the margin and rebuild
byte-identically; a label on its own bar is a contrast defect; a mutation sweep never throws; a changed description exits 2,
tampered records 3; a planted over-wide label and the other unfit cases exit 4 or 5 writing nothing;
invalid descriptions exit 6. LAST, `fixtures/desc_corpus.py`: every file of `fixtures/desc_negative/`
is refused and every file of `fixtures/desc_positive/` accepted, each by press's reader for its `kind`
(a chart envelope by `chart/desc.py`), and each directory matches its `manifest.json` both ways; a
missing or empty directory fails.

## The paged layout pass — what went wrong where the browser broke the pages

`press pdf` prints TAGGED and joins every DOM node to its printed glyphs: Chromium's tagged PDF names
each structure element `/ID "node<N>"`, the same number CDP calls `backendNodeId`, and each element's
marked content (page, MCID) covers exactly its glyphs. Nothing is injected and the layout does not
change. One PDF reader does the readback: pdfplumber (pinned 0.11.4, over pdfminer.six) for the
glyphs, the drawings and — through pdfminer's objects — the structure tree (`press/paged/model.py`;
it patches one pdfplumber bug: nested marked content dropped the owner of every tabular digit).

**The join proves itself on every print.** The measured shares must meet the
floors defined in `press/paged/model.py`:
structure elements whose node id is in the DOM snapshot; of those with an HTML type (P, TD, LI, H2,
Table, Figure …), the ones whose node IS that tag; and, measured from the glyphs, the printed glyphs
whose owner is a text node whose own characters account for them. Under a floor the print FAILS with
exit 7 and says the tagged-PDF join no longer holds (a Chromium upgrade is the likely cause) — never
a quiet "no findings". An untagged print, renumbered ids and a DOM paired with another page's PDF all
fail it. Text counts as rendered only when the snapshot's layout tree gives it an inline text box
(a closed `<details>` does not), and text that legitimately did not print (a run pushed off the
page) is `text-not-printed`'s finding, not a broken join. A page with no text that paints (an empty
body, only images, an empty file) is bad input: exit 6.

**A printed page must be self-contained.** Every request other than the document itself and `data:`
URLs — a sibling stylesheet, an `@import`, a `file://` iframe or image, http — is ABORTED and
reported as a blocking `external-resource` finding (`--allow-layout external-resource` lets them
through and still records each).

| detector | fires when | severity |
|---|---|---|
| `split-table`, `split-list` | a table or list prints on two pages | fix when short enough to have moved whole or when a small stub remains; the thresholds in `split_container` distinguish these from ordinary flow |
| `row-split` | one table row on two pages (a repeated `<thead>` row is exempt) | blocking |
| `heading-stranded` | a heading ends its page; what it heads starts on the next | blocking |
| `intro-stranded` | a line ending in `:`, a caption or `.keep-next` ends its page; also a figcaption on another page than its figure | blocking |
| `runt-page` | a short spill or a sparsely filled page mid-section, under the thresholds in `runt_pages` | blocking |
| `orphan-line`, `widow-line` | one line of a paragraph alone at a page foot / head | fix |
| `overprint` | glyphs of different text over each other, or text inside a figure's ink | blocking |
| `outside-printable` | ink beyond the `@page` margins (drawings: fix) | blocking |
| `starved-column` | a cell's prose repeatedly wraps to very short lines; excludes code cells and lists wrapping at their own separators (`·` `•` `\|` `,` `;` `/`) | blocking |
| `broken-token` | a monospace token broken mid-word where the source had no break point (at `-` or `:`: info) | blocking |
| `figure-text-below-floor` | figure text under the detector's minimum point size on paper | blocking |
| `section-not-page-start` | a numbered `H1`–`H3` ("3. …") with print above it on its page | blocking |
| `text-not-printed` | source characters with no glyphs (Chromium drops text wholly outside the clip) | blocking |

`pdf_report.json` carries `html_sha256` (the bytes read BEFORE the print; a file that changes during
the print is refused, exit 6), ONE findings list `findings` — `{detector, page, element, measure,
severity, allowed}`, severity `blocking | fix | info` — and `layout` =
`{mechanism, status, join, counts, allowed, seconds}`. The stdout line and `press pdfcheck` use the same
three words. Only a blocking finding that is not allowed fails the print (exit 1). `--allow-layout
a,b` (given once; a comma list) records the named findings instead; `layout.allowed` is their sorted
list (`[]` without the flag); an unknown name, `layout-join`, or the option twice exits 6. A broken
join writes one blocking `layout-join` finding on page 0 (the whole document) and exits 7.

Every expectation of the selftest is pinned by hand in `fixtures/paged_fixtures.py` — the exact set
of `(detector, severity)` and the exit code per page — and a copy of the tool with one severity
demoted must turn it red (the `control` section).

Dedicated controls check the detector refinements by undoing them and requiring
the good fixture to go red: a list of names in a narrow cell (`starved_list`) and a list of long items flowing onto the
next page with one item starting there (`split_list_stub`).

**`press pdfcheck <file.pdf> --margins T,R,B,L [--json r.json]`** — for an archived PDF with no HTML.
Without the DOM only a subset can run; `press/paged/pdfonly.py` defines that set, including:
`runt-page`, `heading-stranded`, `intro-stranded` (it misses intro lines that do not end in `:`),
`section-not-page-start`, `figure-text-below-floor` (ANY text: figure text cannot be told apart),
`overprint`, `outside-printable`, `split-table` (ruled grids). The report's `not_run` names the excluded checks and their reasons. Margins cannot be read from a
PDF, so they are required. Exit 1 on a blocking finding; a PDF the reader cannot parse (corrupt,
truncated) or one with no text layer (a scan) is refused with exit 6, saying which.

## Charts — data in, a checked drawing out

`press chart <chart.fig.json> <pick.json> <out.svg> [--json report.json]` draws a bar chart, a stacked
bar (such as a volunteer-hours allocation) or a timeline (lifelines, messages, boxes) from a description with no coordinates
(`press/chart/`). It uses the figure pipeline's house constants (`house.json`), text measurement
(`fig/measure.py`, HarfBuzz) and element writers (`fig/render.py`), and the figure checker judges
the result. Deterministic: two runs write identical bytes. Text is never scaled and keeps the house
margin from the canvas edge: notes and panels wrap inside it, and a chart that cannot fit is REFUSED with what it needs (exit 4: a
label that leaves its bar no room, a segment label wider than its segment, a word wider than the
canvas, a box wider than the canvas, colliding boxes; exit 5: taller than the house budget). Only a clean chart writes anything: the SVG
and the pick record `{id, problem_hash, svg_sha256, renderer: "chart"}`. `press fig rebuild <chart>
<pick> <out.svg>` redraws it with a diagram's refusals (2: the description changed; 3: the record is
malformed or the bytes differ). A chart handed to `press fig solve` exits 6 pointing at `press chart`.

The description is canonical JSON like a diagram's (integers within the reader's bounds; a fraction
is a decimal STRING such as `"19.5"`, drawn as written). Colours are house colour keys (`NAVY`,
`TEAL`, `MID`, `REDWASH` …). Unknown keys are errors everywhere.

| `chart` | `data` |
|---|---|
| `bar` | `title`, `rows: [{label, value ≥ 0, tone, note?, emphasis?}]`, `notes?: [text]` |
| `stacked` | `bars: [{title, total > 0, segments, free-label?, legend?: [{of, text}], note?}]`, `notes?`; a segment is `{id, size > 0, fill, outline?, label?, inside?: [segments]}` (inside: one level, drawn over the start of its parent); sizes add up to at most the total |
| `timeline` | `lifelines: [{id, title, sub?}]` (at least 2); `steps`, each `{"step": "msg", from, to, text: [lines], look?: solid or dashed, tone?: NAVY MUTED TEAL AMBER RED, text-tone?}`, `{"step": "box", at, title: [lines], sub?, tone?}`, `{"step": "break", text}`, `{"step": "boxes", items: [{at, title, sub?}]}` or `{"step": "panel", text}` |

A message crossing another lifeline is a checker defect (`crossing`). Choose
units that fit the numeric bounds in `press/chart/desc.py`. The examples in
`fixtures/charts/` show fictional borrowing counts, volunteer-hour allocations
and a lending conversation.

## Layout

- `press/deck_qa.py`, `press/deck_qa_js.py` — the tool and its in-page probes.
- `fixtures/build_fixtures.py` — two decks that differ only in whether each
  defect is present; `fixtures/media/` their small ffmpeg-built clips and PNGs.
- `fixtures/selftest_verdict.py` — PASS only when every detector is red on the
  bad deck and the good deck is clean.
- `press/deck_pdf.py` — the `pdf` verb (Playwright tagged `page.pdf` + `pdftoppm` + the layout pass).
- `press/paged/` — `model.py` (the DOM to tagged-PDF join and its proof), `detect.py` (layout detectors), `pdfonly.py` (`press pdfcheck`).
- `press/chart/` — `desc.py`, `render.py`, `cli.py` (`press chart`).
- `fixtures/paged_fixtures.py` + `paged.css` — the planted paged pages and their controls;
  `fixtures/paged_selftest.py`, `fixtures/paged_launcher_selftest.sh` — their proof.
- `fixtures/charts/`, `fixtures/chart_selftest.py`, `fixtures/chart_launcher_selftest.sh` — charts.
- `fixtures/desc_corpus.py` — the shared description corpus check (`fixtures/desc_negative/`,
  `desc_positive/`, written by press.doc's builder).
- `press/house.json` — the house constants for figures.
- `press/fig/` — the figure pipeline: `desc.py` + `view.py` (the description), `measure.py`,
  `model.py` (the one rule writer, CP-SAT and Z3 backends), `place.py`, `explain.py`,
  `route.mjs`, `render.py`, `check.py`, `sheet.py`, `budget.py`, `cli.py`.
- `press/fig/record.py` — the pick record checked against its description (no solver).
- `fixtures/figs/` — the reference descriptions; `fixtures/fig_plants.py`,
  `fixtures/fig_selftest.py`, `fixtures/fig_launcher_selftest.sh` — the figure selftest.
- `pins/` — the lock files the image installs from.
- `bin/press` — the host launcher; `Dockerfile` — Playwright + fonts + Pillow +
  poppler-utils, plus node + libavoid-js, OR-Tools, Z3, HarfBuzz.

The Clojure checks and generated corpus freshness run with
`make -f lint.mk press-test`. The browser proof runs separately with
`make -f press.mk selftest`; review the generated page and figure PNGs as well.

`press.yml` runs the browser proofs in CI; `lint.yml` runs the Clojure suite,
publication-gate canaries and generated corpus comparison. Image identity is
derived by `bin/image-tag` in a fixed locale from named Dockerfile and pin digests.
Changing those inputs requires a new image; the launcher names that image when
it is missing.

Before completing changes to the publication gates, run the affected mutation
campaign from a committed, clean checkout:

```sh
python3 tools/mutation/campaign.py tools/press/test/gate-faults.json .fork-scratch/press-gates
```

Each fault runs in a disposable clone against the pinned toolchain. A named
failing assertion and a passing neighboring control establish a kill; a crash
or timeout does not. Raster, house-path and pagination changes also require
their affected tests and manual mutation assessment. Public fixture origins
and the readability of every printed page require independent review.
