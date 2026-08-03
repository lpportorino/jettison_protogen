# Dump-buffer occupancy — what actually caps a pooled widget, measured

> **PUBLIC-REPO GUARDRAIL.** This document describes this repository's own
> renderer and harness. It names no deployment, no host, no operator path and no
> consuming project.

This is a MEASUREMENT DOCUMENT. It changes no `.proto`, no `.options` and
nothing under `renderer/src/`. It exists because a consumer of this renderer
wants a statically-allocated pool of overlay boxes and needs a number to size it
with, and every candidate number so far has been an argument rather than a
measurement.

Everything below was produced by driving the real `controls.wasm` through the
wasmtime harness. Reproduce it with `tools/perf/dump_buffer_probe.sh`; the
fixtures come from `tools/perf/dump_buffer_fixture.py` and the per-node
breakdowns from `tools/perf/dump_tree_census.py`.

## The short answer

**There are FOUR ceilings, not one, and which one binds depends on the shape of
the pooled element — not on its count alone.** Exactly ONE of them refuses the
load outright. Two return a nonzero status and leave the screen RENDERING with
degraded nodes. One truncates the dump and touches nothing else.

| ceiling | value | where | what happens | last good / first bad element count |
|---|---|---|---|---|
| subject registry | 32 declarations | `renderer/src/renderer.c` `MAX_SUBJECTS` | `controls_load_ui` returns `-2`; screen RENDERS, surplus subjects dropped and every binding to one is dead | not swept — read from source |
| uid registry | 1024 uid-bearing nodes | `renderer/src/renderer.c` `MAX_UID_NODES` | `controls_load_ui` returns `-2`; screen RENDERS, surplus nodes unidentified | 512 / 513, at 2 uid nodes per element |
| dump buffer | 131072 bytes | `renderer/src/main.c` `TREE_BUF_SIZE` | dump TRUNCATES, load and render unaffected | 578 / 579, with no uids |
| style pool | 2048 styles | `renderer/src/renderer.c` `MAX_STYLES` | `controls_load_ui` returns `-1`; the partial tree is TORN DOWN, screen not built | 2047 / 2048, at 1 style group per element |

`-1` and `-2` are not two spellings of failure. `renderer/src/renderer.h`
defines `LOAD_ERR_ABORTED` as `-1` and `LOAD_ERR_DEFECTIVE` as `-2`, and its
header block states the difference decides whether the caller may leave the
screen up: ABORTED means a decode callback returned false, so the tree is
truncated at the fault and nothing usable was built; DEFECTIVE means the decode
ran to completion, so the tree is COMPLETE and renderable with individual nodes
degraded. `controls_load_ui` in `renderer/src/main.c` acts on exactly that
split — its `lv_obj_clean(lv_screen_active())` is scoped to `LOAD_ERR_ABORTED`
alone.

So the claim *"the dump buffer is the binding constraint"* is **true only for a
pool whose nodes carry no uid**. `ui.WidgetNode.uid` is described in
`proto/ui/ui_ast.proto` as assigned by codegen, and the renderer registers every
NONZERO uid; a pool whose nodes are all codegen-identified therefore reaches the
uid registry at 512 two-node elements, before the dump is ever consulted.

**That is the WORST of the three outcomes for a pool design, not the safest.**
The screen comes up looking correct while the surplus elements have no identity,
so the failure is invisible at the point it occurs and surfaces later, somewhere
else: a `ScreenPatch` op addressing one of those elements cannot find it and
returns `PATCH_ERR_UNKNOWN_UID`, and — being an abort other than the
pre-mutation base-hash refusal — latches the tree INDETERMINATE, so
every subsequent patch refuses with `PATCH_ERR_INDETERMINATE` until a full
`controls_load_ui`. A truncated dump costs a consumer its view of the tree; an
overrun uid registry costs it the ability to update the tree at all, from a
screen that gave no sign of being over the line.

The 255 an operator asked for is comfortably inside the three MEASURED ceilings
for every element shape swept here. At 255 elements the dump sits between 18.2%
of the buffer (an element with no text child) and 67.5% (an element with a
64-byte label) — 41.7% for the element shape the question is actually about —
and the uid registry at 49.8% for a pool with a uid on every node. That the
range is a factor of 3.7 wide at a FIXED count is the whole reason a single
"bytes per box" number cannot size this.

**The subject registry is the exception, and it is not close.** Every element
swept here is statically valued, so none of them declares a subject and no sweep
touched that ceiling. It is 32 declarations for the WHOLE screen, shared with
everything else on it, so a pool whose elements each carry their own reactive
value is over the line at the 33rd element, and sooner by however many the rest
of the screen already declares — more than an order of magnitude below every
other number in this document. A pool of that shape has to bind its elements to
a shared subject, or index into one, or not be reactive at all.

## The ceiling, read from source

`renderer/src/main.c` declares:

```c
#define TREE_BUF_SIZE 131072u
static char tree_buf[TREE_BUF_SIZE];
...
static json_out_t tree_out = {tree_buf, TREE_BUF_SIZE, 0, false};
```

The usable payload is `TREE_BUF_SIZE - 1` = **131071 bytes**, because
`json_append` reserves the NUL. Every truncated dump measured here is exactly
131071 bytes long, which is the check that the source reading and the artifact
agree about the same constant.

The buffer is SCREEN-WIDE. `controls_dump_tree` walks `lv_screen_active()`, so a
pool shares the buffer with everything else on the screen. Measured against this
repository's own authored screens at 960x540, bp0/light:

| screen | dump bytes | nodes | bytes/node |
|---|---|---|---|
| `renderer/edn/screens/tabview_demo.edn` | 3395 | — | — |
| `renderer/edn/screens/kitchen_sink.edn` | 9521 | 70 | 136.0 |
| `renderer/edn/screens/demo_widgets.edn` | 21062 | 153 | 137.7 |

A pool's budget is therefore 131071 MINUS whatever the rest of the screen costs
— on the order of 3–21 KB for screens of this repository's own size, and a real
instrument screen carrying a pool as well as its ordinary controls would sit at
the upper end or past it.

## What one pooled element costs

The element measured is the one the question is about: a container carrying a
background fill and a border, holding one text child. **That is TWO `lv_obj`
nodes**, and treating it as one is the first thing a per-element estimate gets
wrong.

**The border costs nothing in the dump.** `dump_obj` emits no border key at all,
so the "background and border" in the element description contributes exactly
the 21 bytes of `,"bg_color":"#203040"` and nothing more. Every other key it
emits is conditional.

### The cost is not a per-node constant

`dump_obj` emits most keys only when the value DIFFERS from an inherited or
default one, so a node's cost depends on where it sits and what it holds. Census
of two real dumps of the same element shape, no uids, flex-wrapped:

| | 16 elements | 512 elements |
|---|---|---|
| nodes | 34 | 1026 |
| dump bytes | 3249 | 115337 |
| mean bytes/node | 95.6 | 112.4 |
| `lv_label` self-bytes (min/median/max) | 110 / 113 / 114 | 110 / 127 / 129 |
| `lv_obj` self-bytes (min/median/max) | 73 / 76 / 145 | 73 / 106 / 145 |
| nodes emitting `vis_px` | 0 | 764 |
| nodes emitting `clipped` | 0 | 382 |

The same node shape costs 18% more per node once the pool overflows the visible
area, because two keys start firing that were silent before. Marginal bytes per
ELEMENT, read off successive sample points of the same sweep:

| element index range | bytes per element |
|---|---|
| 2 → 4 | 187 … 190 |
| 8 → 16 | 189.6 |
| 96 → 128 | 195.4 |
| 128 → 160 | 231.3 |
| 448 → 512 | 236.3 |

### And it is not a constant across element shapes either

Every row below is the same sweep against a different element shape; the count
is the LAST count whose dump is whole.

| element shape | last whole count | bytes there | first truncated |
|---|---|---|---|
| no uid, no text child | 1251 | 131062 | 1252 |
| no uid, absolute placement, 1-char label | 587 | 130867 | 588 |
| no uid, flex, 1-char label | 578 | 130934 | 579 |
| uid on the container only | 550 | 130826 | 551 |
| no uid, flex, 16-char label | 430 | 130990 | 431 |
| no uid, flex, 64-char label | 372 | 130832 | 373 |
| uid on every node | 512 | 127445 | never — the load goes DEFECTIVE at 513 |

**A 3.4x spread between the cheapest and dearest element**, from content alone.
Two of the levers are worth naming:

- **The text child dominates.** Dropping it more than doubles the capacity
  (578 → 1251), because the label node carries `text`, `clickable:false` and
  `backdrop_unresolved:true` on top of its own type and coords.
- **Label text is capped at 64 INPUT BYTES** by `tree_append_json_str`, and the
  cap is real: a 96-character label produced a dump 2 bytes larger than the
  64-character one at the same count, not 32 bytes per node larger. The cap
  counts bytes, not codepoints — `json_append_str` indexes `s[i]` — so a label
  of non-ASCII glyphs reaches it sooner than its character count suggests, and
  an escaped control character can expand to six output bytes from one input
  byte. Both directions matter when estimating from a string's length: the
  dump buffer counts BYTES throughout. This repository's own `demo_widgets`
  dump is 21062 bytes against 21056 characters, from 9 non-ASCII bytes.

The exact literal costs of the keys that move, for reference when estimating a
different element:

| emitted fragment | bytes |
|---|---|
| `{"type":"lv_obj","coords":[` | 27 |
| `{"type":"lv_label","coords":[` | 29 |
| `,"children":[` + `]}` | 15 |
| `,"bg_color":"#203040"` | 21 |
| `,"backdrop_unresolved":true` | 27 |
| `,"clickable":false` | 18 |
| `,"clipped":true` | 15 |
| `,"hidden":true` | 14 |
| `,"vis_px":0` | 11 |
| `,"uid":1000` | 11 |

Coordinates are decimal, so a node's cost also grows with the MAGNITUDE of its
resolved position — a pool that scrolls past y=999 pays a byte per node per
extra digit.

## The overflow count and the failure mode

There are THREE failure modes across the four ceilings, and they are not ordered
by severity the way their return values suggest. The nonzero status is what
distinguishes them, so read the status, never merely `!= 0`.

**Failure mode 1 — hard REFUSAL, when the style pool fills.** This is the only
ceiling here that stops the decode. `alloc_style` returns NULL,
`properties_decode_cb` returns false, nanopb stops mid-stream, `pb_decode`
fails and `build_ui_from_proto_raw` returns `LOAD_ERR_ABORTED` (`-1`);
`controls_load_ui` then cleans the screen. Bisected exactly: 2047 elements each
carrying one style group load; 2048 do not (`style pool exhausted (2048 max)`),
return `-1`.

One arm of style-pool exhaustion does NOT behave this way, and it was read from
source rather than swept: `apply_scale_section` calls `alloc_style` and on NULL
simply returns, latching nothing. Every neighbouring pool site in that file —
`apply_scale_text_src`, `apply_buttonmatrix_map`, `apply_line_points` — sets
`load_resource_error` on exhaustion; this one does not, so a scale section that
loses its style is the one overflow in this document that can end at status `0`.
It is out of reach of a pool of plain boxes and is recorded because "the style
pool always refuses" is the kind of generalisation this document exists to stop.

**Failure mode 2 — DEFECTIVE, when the uid registry or the subject registry
fills.** `controls_load_ui` returns `LOAD_ERR_DEFECTIVE` (`-2`) and **the screen
is built and RENDERS.** Nothing is torn down, and that follows from the control
flow rather than from a policy choice at the call site: neither overflow stops
nanopb. `subjects_decode_cb` drains the element it cannot store and returns
true; a uid overflow latches `ctx->error` inside `finalize_widget` and
`children_decode_cb` latches it upward and returns true as well — deliberately,
because that same latch carries the duplicate-uid case, which is contracted to
stay renderable, so aborting on it would truncate the tree at every collision.
The decode therefore runs to completion, the tree is whole, and the surplus
nodes are degraded rather than absent. Bisected exactly for the uid case: 512
elements with a uid on both nodes load clean; at 513 the log names it (`uid
registry full (1024 max)`) once per offending node, the call returns `-2`, and
the screen comes up with those nodes unidentified.

**This is the mode to design against, and its shape is what makes it
dangerous.** It is not loud where the operator is looking — the render is
correct — and it is not early, because it surfaces at the first patch that
addresses a node past the line. `register_uid` refuses the surplus node, which
then behaves exactly as a legitimately unidentified (`uid == 0`) one; a later
`ScreenPatch` op naming that uid finds nothing and returns
`PATCH_ERR_UNKNOWN_UID`, which `controls_apply_patch` treats as a
possibly-mutating abort and latches the tree INDETERMINATE. Every further patch
then refuses with `PATCH_ERR_INDETERMINATE` until a full `controls_load_ui`. So
the cost of crossing this ceiling is not a blank screen; it is a screen that
looks right and has silently lost the ability to be updated.

A consumer that treats any nonzero load status as "screen not built" therefore
gets this backwards in the expensive direction, and so does one that tears the
screen down on `-2`: that blanks a working screen over a defect confined to a
few nodes. `renderer/src/main.c` scopes its own teardown to `LOAD_ERR_ABORTED`
alone, and a consumer's recovery policy has to make the same split.

**Failure mode 3 — TRUNCATION, when the dump buffer fills.** The load succeeds,
the render is correct, and only the dump is damaged. It is not a crash and it is
not a refusal. Characterised on the first truncating count of the no-uid sweep
(579 elements):

- the artifact is exactly **131071 bytes**, every time, for every count past the
  edge;
- it **always ends with `,"truncated":true`** — `controls_dump_tree` overwrites
  the tail with that sentinel expressly so the damage is detectable;
- it is **NOT a prefix of the whole dump**. `json_append` DROPS an append that
  will not fit and returns, but it does not latch shut, so a later SMALLER
  append still lands. The measured tail reads
  `..."children":[,"vis_px":0,"text","truncated":true` — a `[` immediately
  followed by `,` cannot occur in any prefix of a complete dump, because
  `dump_obj` writes that separator only BETWEEN children;
- it is structurally unbalanced (1159 `{` against 1156 `}`) and does not parse.

**The two hosts in this repository disagree about what to do with it, and a
consumer copying either inherits its behaviour.**

- `tools/devcards/src/devcards/host.clj` has a truncation membrane
  (`normalize-dump`): it checks the sentinel and replaces the WHOLE dump with
  `{"truncated":true,"children":[]}`. On overflow a devcards consumer therefore
  loses the ENTIRE tree, not merely the tail.
- `renderer/wasm_harness/src/wasm_host.rs` `dump_tree` does NOT check the
  sentinel. It returns the cut string as-is, and the CLI writes it to disk and
  logs a byte count with no warning.

For a pool design that means: crossing the dump ceiling does not degrade the
overlay's rendering, but it blinds every consumer that reads the tree — for the
Clojure host, completely and at once.

## Do hidden children still cost? Yes — and they cost MORE

`dump_obj` ends with

```c
uint32_t n = lv_obj_get_child_count(obj);
for (uint32_t i = 0; i < n; i++) { ... dump_obj(lv_obj_get_child(obj, i), false); }
```

with no visibility test anywhere in the recursion. So the design pass's premise
holds. The measurement sharpens it in the direction that matters for a static
pool: **hiding is not merely free of savings, it is a surcharge.** A hidden
container adds `,"hidden":true` (14 bytes), and a not-fully-visible node adds
`,"vis_px":0` (11 bytes) — which fires on the container AND on its text child.

Measured under ABSOLUTE placement, where the coordinates are held fixed so the
comparison isolates the dump:

| elements | visible | hidden | hidden surcharge |
|---|---|---|---|
| 4 | 972 | 1116 | +14.8% |
| 16 | 3248 | 3824 | +17.7% |
| 64 | 12540 | 14844 | +18.4% |
| 128 | 25048 | 29656 | +18.4% |
| 255 | 52735 | 62723 | +18.9% |
| 384 | 82882 | 99062 | +19.5% |
| 512 | 113137 | TRUNCATED | — |

**A trap worth recording, because it points the wrong way.** Under FLEX layout
the hidden pool looks cheaper in bulk — 124969 bytes against 127445 at 512
elements. That saving is not the dump's. LVGL's flex layout skips hidden
children, so all 512 hidden boxes resolve to the same coordinates
(`[1,1,60,40]`, verified in the artifact) and their coordinate digits collapse,
while the visible ones spread to four-digit y values and pick up `clipped`. The
absolute-placement pair above removes that confound and the crossover disappears
at every count.

## Does the measured curve agree with what the source implies?

**In the on-screen regime, to the byte. Past it, no — and a formula fitted there
oversizes the pool by 19%.**

For this element shape the source implies a per-element cost of
`172 + digits(container coords) + digits(label coords)`, from the fragments
tabulated above. Checked against the artifact at 3 elements: element 2 sits at
`[75,1,134,40]` with its label at `[79,5,90,22]`, so 172+8+7 = 187, and the
measured marginal from 1→2 elements is 592-405 = **187**. Element 3 sits at
`[149,1,208,40]` with its label at `[153,5,164,22]`, so 172+9+9 = 190, and the
measured marginal is 782-592 = **190**. Exact, twice.

The disagreement starts where the conditional keys do. A naive constant model
fitted on those same early points — `bytes(N) = 405 + 190·(N−1)` — predicts the
buffer fills at **688** elements. The measured answer is **578**. The naive
model oversizes the pool by 110 elements, **19%**, and it fails in the unsafe
direction: it would authorise a pool that truncates in the field.

Nothing about that is exotic. It is `vis_px` and `clipped` firing on nodes that
leave the visible area, plus a byte per extra coordinate digit — three effects a
per-node constant cannot express, and all three of which get WORSE as the pool
gets larger, which is precisely when the estimate is being trusted.

## What this does not establish

- **It measures a pool ALONE**, above a two-node root. A real screen's other
  widgets take their share of the same 131071 bytes, of the 1024 uid slots and
  of the 32 subject declarations; the baseline table above is the closest this
  repository can come to that, from its own authored screens.
- **The subject registry was never swept.** No element shape here declares a
  subject, so `MAX_SUBJECTS` is read from source and stated, not measured, and
  no last-good / first-bad pair for it exists. It is the one ceiling in the
  summary table carrying no bisection behind it.
- **The element is a MINIMAL one.** It carries one style group, a one-character
  label by default, no event binding, no bindings map and no visibility binding.
  Real authored nodes in this repository's own screens average 136–138 dump
  bytes each against this element's 95–112, so a realistic pooled element is
  DEARER than the numbers here, not cheaper.
- **The LVGL heap was never reached.** `renderer/lv_conf.h` sets
  `LV_MEM_SIZE` to 2 MiB; no sweep here exhausted it, because one of the three
  MEASURED ceilings above always arrived first. A pool of heavier widgets could
  reorder that.
- **Only bp0 / 960x540 was swept.** Theme was checked and does not matter: the
  same 255-element pool dumps to 54608 bytes in both light and dark. Breakpoint
  tier was not swept, and it moves resolved geometry, so it moves coordinate
  digits.
- **Nothing here says what the renderer SHOULD do.** Raising `TREE_BUF_SIZE`,
  making the dump skip hidden subtrees, or giving the Rust host the membrane the
  Clojure host has are all changes to a contract a fleet of consumers pins, and
  each is a separate decision.

## Reproducing

```
tools/uber.sh 'make -f renderer.mk wasm'
tools/uber.sh 'cd renderer/wasm_harness && cargo build --release'
tools/uber.sh 'bash tools/perf/dump_buffer_probe.sh'
```

The probe prints one TSV row per sample point. `COUNTS` and `VARIANTS`
environment variables select the sweep; the variant arms are listed in the
script. `tools/perf/dump_tree_census.py` takes any dumped `*.tree.json` and
reports the per-node distribution and key frequencies behind the tables above.

### The sample points behind these tables

Every figure above is one harness invocation; none is interpolated, and no
edge is extrapolated — each last-good / first-bad pair is two adjacent counts,
both run.

| sweep | counts | variants | invocations |
|---|---|---|---|
| primary | 26 | 3 | 78 |
| shape variants | 30 | 5 | 150 |
| ceiling neighbourhood | 20 | 3 | 60 |
| dump-edge bisect, round 1 | 9 | 3 | 27 |
| per-variant bisect, round 2 | 10+8+7+7+6 | 1 each | 38 |
| per-variant bisect, round 3 | 7+1+6+6+5 | 1 each | 25 |
| hidden vs visible, absolute | 7 | 2 | 14 |
| style-pool probe | 6+4 | 1 | 10 |
| smoke / arm check | 3+9 | 3 and 9 | 9 |

411 in total, which the probe leaves countable rather than asking to be
believed: it writes one `<variant>_<count>.log` per sample under its
`SCRATCH` directory, so `ls "$SCRATCH"/out/*.log | wc -l` is the accounting.
A handful of further invocations sit outside that: the three authored-screen
dumps in the baseline table, and one dual-theme run behind the
theme-does-not-matter claim.
