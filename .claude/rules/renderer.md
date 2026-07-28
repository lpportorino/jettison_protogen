---
description: Working rules for the ui_ast reference interpreter (renderer/ C source, controls.wasm, the wasmtime proof battery). Loads when editing the renderer tree.
paths:
  - "renderer/**"
  - "renderer.mk"
  - "tools/renderer-gen/**"
---
<!-- LOAD-TEST: renderer -->

# Renderer (ui_ast reference interpreter) — working rules

protogen owns the ONE reference interpreter end-to-end: the C renderer under
`renderer/src/`, its theme, the vendored `renderer/lvgl/` tree, `wasm.mk`
(builds `controls.wasm` / `reference.wasm`), and the proof battery. It is a
generated projection of this repo's own sources — the same artifact class as the
language bindings, not a hand-maintained app.

## Battery — green before any renderer change lands
- Entry, from the repo root inside the `Dockerfile.base` toolchain container (the
  host has no WASI-SDK): `make -f renderer.mk check-renderer`, or the
  `/check-renderer` skill. The lanes and their order are the `check-renderer`
  target in `renderer.mk`. CI never invokes that target: `renderer.yml`
  DECOMPOSES it into individual steps and `devcards.yml` runs others, so the set
  CI covers is a SUBSET — a lane omitted from both workflows runs only locally.
- CI's fixtures/gallery jobs consume an ALREADY-BUILT `controls.wasm` (the
  `*-prebuilt` targets, guarded by `wasm-present`) — a missing wasm is a
  sequencing bug, never a skip.
- **The battery builds INCREMENTALLY, so prove the tree was clean by the COMPILE
  COUNT — exit 0 cannot.** No lane cleans `renderer/build/`, and `wasm.mk`'s
  `-MMD -MP` depfiles track HEADERS, never the COMPILER: swap what sits at
  `$(WASI_SDK)` — an image rebuild, or a host clang at that path — and not one
  object is invalidated. The next build then compiles ZERO files, relinks
  nothing, prints success, and every gate judges an artifact this toolchain
  never produced. Nothing downstream sees it: the module loads and the
  provenance stamp still names the right commit. The tell is arithmetic —
  `make -f wasm.mk -n all reference OBJ_DIR=<scratch> | grep -c ' -c -o '`
  prints what a from-scratch build owes (557 on this tree, equal to the `.o`
  count under `build/release`; `all` alone is 546). A run reporting fewer reused
  that many stale objects, so `make -f wasm.mk clean` and re-run the lane before
  any claim rests on it.
- **LANE COVERAGE IS NOT NESTED, and `demo-parity`'s green is the one most
  likely to be over-read.** It renders exactly one screen,
  `renderer/edn/screens/demo_widgets.edn`, which authors three of the eight
  `lv_flex_flow_t` values — and a `:layout` grep under-counts them, because the
  `flex` / `flex-row` / `flex-col` CLASS utilities set the flow too
  (`layout-flow` in `tools/renderer-gen/src/lvgl_codegen/expand.clj`). The
  `matrix` lane's `FLEX_KWS` (`renderer/coverage_matrix/run.sh`) drives all
  eight. **The five it does not share appear nowhere else in the tree** — not in
  a screen, not in a devcard fixture, only in the codegen vocabulary and in that
  script — so `matrix` is their SOLE rendering gate, and likewise for every
  direct-cast enum family, which has no LUT for a static gate to read.
  `construct-bindings` byte-compares `renderer/generated/ui_luts.h` against the
  vendored LVGL headers cheaply, but it is blind one leg upstream at the
  authoring-keyword → wire-number map
  (`tools/renderer-gen/src/lvgl_codegen/emit_proto.clj`), so it is a complement
  and never a substitute. Read each lane's green as ITS OWN clause.
- **No standing canary covers the wire-number LUTs.** `renderer.mk`'s canary
  targets serve the deadzone lane and the review preflight; nothing fires for a
  wrong-but-legal `flex_flow_lut` entry, which is precisely the class
  `ui_luts.h`'s own banner names as uncatchable by the C compiler — the header
  IS the declaration. That `matrix` is non-vacuous here rests on a hand-run
  mutation, not on a lane, and that is a gap rather than a decision.

## The determinism contract — change all-or-none
- The pinned render protocol (tick budget, tick ms, DPI, canvas) is restated in
  several homes that MUST stay equal: `render-protocol` in
  `tools/devcards/src/devcards/core.clj` (and `host.clj`'s
  `render-ticks`/`tick-ms`/`default-dpi`), `renderer/wasm_harness/src/lib.rs`
  (`RENDER_TICKS` / `TICK_MS`), and every golden manifest's `:protocol`. A render is a deterministic function of module +
  fixture + tick budget — never an adaptive settle. Change the constant in every
  home or none.
- The framebuffer is straight (non-premultiplied) alpha in the format
  `controls_fb_format` reports: color bytes under A=0 are garbage, so consumers
  MUST composite/flatten. Goldens hash the RAW framebuffer bytes, never an
  encoded image.
- Source link order IS the wasm byte layout — `wasm.mk` sorts sources on purpose.

## ABI discipline
- `controls.wasm` self-describes via `controls_abi_version` + `controls_fb_format`
  (`CONTROLS_ABI_VERSION` lives in `renderer/src/main.c`). The `supported-abis`
  gate in `host.clj` fails loud on mismatch. Bump the ABI DELIBERATELY with the
  wasm pin, and update the wire contract (`docs/INTERFACE-CONTRACTS.md`) + the
  consumers in the same coordinated event.
- The mandatory `env` imports (`renderer/src/host_imports.h`) are
  instantiation-mandatory for `controls.wasm`; a host that omits one cannot load
  the module.
- **`output/controls.wasm.build-sha` is SOURCE provenance, never TOOLCHAIN
  provenance.** `PROTOGEN_SHA` (`wasm.mk`) is `git rev-parse HEAD` plus a
  `-dirty` suffix, degrading to a literal that matches no gitlink so "cannot
  answer" reads as NO. It records nothing about which IMAGE compiled the bytes,
  and that gap is reachable rather than theoretical — `uber.sh` reuses whatever
  base image is already present. So a stamp matching a consumer's gitlink
  establishes the source and never the toolchain; only building in the pinned
  image does that.

## LVGL traps — one HANGS, the rest go quietly wrong

Measured by registering a second `lv_draw_unit_t` (a read-only observer) against
LVGL as vendored. **THE OBSERVER IS NOT IN THIS TREE** — it lived in a scratch
fork, and only its bundle survives. So every COUNT below is report-sourced and
not re-derivable by grep; the mechanisms and file references beside them are,
and were re-verified against the vendored source. Treat the numbers as recorded
observation and the citations as checkable — and if you rebuild the observer,
re-measure rather than quoting these.
Registering one is not free bookkeeping:
`lv_draw_get_available_task` takes the linear fast path only while
`unit_cnt == 1` (`renderer/lvgl/src/draw/lv_draw.c`), so a second unit moves the
SW unit onto the `is_independent()` dependency walk — a change to task
SELECTION. It came out pixel-neutral across every committed golden, and that golden diff is
the ONLY thing proving it stays neutral across an LVGL bump.

- **An LVGL assert HALTS the guest — it does not abort, and nothing times it
  out.** `LV_ASSERT_HANDLER` defaults to `while(1);`
  (`renderer/lvgl/src/lv_conf_internal.h`) and `renderer/lv_conf.h` does not
  override it, while `LV_ASSERT` / `LV_ASSERT_MSG` are unconditional and
  `LV_USE_ASSERT_NULL` / `_MALLOC` default on. Neither harness bounds guest
  execution — no fuel, epoch or timeout in `renderer/wasm_harness/src/lib.rs` or
  `tools/devcards/src/devcards/host.clj` — so a halted guest hangs the run
  forever: the battery does not go RED, it STOPS, and it is killed by hand.
  (In CI the platform's own job timeout bounds it — that is GitHub's, not ours.)
  The observed trigger was the `lv_inv_area` assert,
  `LV_ASSERT_MSG(!disp->rendering_in_progress, …)` in
  `renderer/lvgl/src/core/lv_refr.c` — invalidating an area while rendering.
- **A draw unit with `dispatch_cb = NULL` is a SEPARATE defect with a DIFFERENT
  failure mode, and welding the two together is wrong.**
  `lv_draw_dispatch_layer` calls `u->dispatch_cb(u, layer)` through no NULL
  guard (`renderer/lvgl/src/draw/lv_draw.c`), but nothing asserts on it: a null
  indirect call under wasm TRAPS, which reddens rather than hangs. Give every
  unit a `dispatch_cb` returning `LV_DRAW_UNIT_IDLE` — just do not expect the
  hang above if you forget.
- **Creating a draw task inside `evaluate_cb` renders the very task under
  evaluation, and THE GOLDENS DO NOT NOTICE** — 1,528 of 1,528 fires went
  `WAITING -> FINISHED`, and NOT ONE committed golden changed. `lv_draw_finalize_task_creation`
  sets `preferred_draw_unit_id = 0` (`LV_DRAW_UNIT_NONE`) BEFORE the evaluate
  loop, `lv_draw_get_next_available_task` accepts exactly that state, and at
  `LV_USE_OS LV_OS_NONE` (`renderer/lv_conf.h`) the SW `dispatch` calls
  `execute_drawing` inline. A 100%-reproducible violation of the task state
  machine whose benign outcome is a property of THIS corpus and THIS build —
  the pixel oracle cannot report it. An observer must not create draw tasks.
- **`LETTER` tasks exist in the enum and are never emitted here.**
  `LV_DRAW_TASK_TYPE_LETTER` is declared in `renderer/lvgl/src/draw/lv_draw.h`,
  and its only producer, `lv_draw_letter()`
  (`renderer/lvgl/src/draw/lv_draw_label.c`), IS linked in and IS called — by
  `lv_arclabel.c`, which `LV_USE_ARCLABEL` leaves on by default and `wasm.mk`
  compiles wholesale. It is never REACHED here for a narrower reason: the ui_ast
  vocabulary has no arclabel, so one is never instantiated. Check the vocabulary,
  not the call graph -- "nothing calls it" is false and would be re-refuted by
  anyone who greps. Text arrives as ONE `LABEL` task per label and the SW unit expands
  glyphs internally: 0 LETTER in 33,510 observed tasks. A gate written to sample
  per-glyph colour observes zero text and reports an empty result, not an error.
- **`glyph_data` is NULL for this renderer's fonts** — 10,408 of 10,408
  callbacks. `lv_draw_unit_draw_letter` (which `lv_draw_label_iterate_characters` calls)
  populates it only in the
  `LV_FONT_GLYPH_FORMAT_VECTOR` branch, and `renderer/src/font_*.c` are C-array
  bitmap fonts. For ink, call `lv_font_get_glyph_bitmap` INSIDE the callback —
  `lv_font_glyph_release_draw_data` runs the moment it returns. For a per-glyph
  BOX, `letter_coords` is already exact and needs no call.
- **PARTIAL mode re-issues each draw task once per strip it touches, and
  non-uniformly.** `init_display` (`renderer/src/main.c`) allocates 64-row strips
  at the `render-protocol` canvas ⇒ 8 strips per frame. Measured repeat over
  33,510 records: 2.98× overall, 1.604× LABEL, 1.000× IMAGE — the multiplier is
  a function of where it sits on the strip grid, so two identical widgets at
  different y carry different weights, and a boundary SPLITS one glyph run across
  two invocations. An accumulator must be keyed on `(type, obj, area)` and
  MERGED, never summed: it is not consistently wrong, so no single correction
  factor rescues a sum.

## Fonts — the TTF on disk is NOT the artifact that renders

`resolve_font` (`renderer/src/renderer.c`) is the whole resolution story and it
is ORDERED: a string-match against the compiled-in fonts first, then
`P:fonts/<name>.bin`, then TinyTTF over `P:fonts/<family>.ttf`. Every `:fonts`
tuple in `tools/renderer-gen/edn/tokens.edn` that the string-match names returns
a `renderer/src/font_*.c` C-array bitmap, generated by `lv_font_conv` at
`--bpp 4` over a restricted `--range` (each file's banner carries its own opts
line). The only tuple that reaches a TTF is the one deliberately left uncompiled
to exercise that path, `:font-ttf-probe`; no `.bin` ships, so its `.bin` leg
always misses.

**A metric read off `renderer/assets/fonts/*.ttf` therefore describes something
that is not being drawn, and it fails QUIETLY — the numbers come out plausible
and wrong.** The compiled tables carry their own `line_height` / `base_line` and
they do not agree with the TTF they were cut from: `font_orbitron_bold_32` says
35 where that face's `hhea` ascender/descender scale to 40.13, and
`font_b612mono_bold_12` says 15 where the same arithmetic gives 14.58. **The
sign flips**, so no single correction factor rescues a TTF-derived number: the
compiled table is the only source of truth for what renders, and it has to be
read directly. Any font-metric measurement records which rasterizer produced it
or it is not a measurement.

**Nor does a TTF hold ONE answer to fall back on.** `b612mono_bold.ttf` ships
`VDMX`, `hdmx` and `LTSH` — per-ppem DEVICE metric tables — and
`Orbitron-Bold.ttf` ships none of them, so settling the question on one face
gets the other wrong. They answer a different question and must never be quoted
beside `line_height`: VDMX reports ink EXTENT at a ppem (at ppem 20, yMax 20 and
yMin -8), never a line advance. Which artifact and which table a number came out
of is part of the number.

The one TTF tuple is the exception that proves the ordering, not a
counterexample: TinyTTF derives `line_height` from `stbtt_GetFontVMetrics` — the
same `hhea` values — and TRUNCATES rather than rounds
(`renderer/lvgl/src/libs/tiny_ttf/lv_tiny_ttf.c`). Know which way a failed load
goes, too: `resolve_font` logs and falls back to `font_b612mono_bold_16` rather
than failing, so a broken TTF path still renders. The `vr_ttf_font` /
`vr_ttf_font_base` fixture pair and the differ between them are the oracle for
that; the log is not.

## Stock colours are a table LOOKUP — nothing about them is computed

Whatever the child theme leaves unset falls through to stock, whose greys
resolve through `lv_palette_main` / `lv_palette_lighten` / `lv_palette_darken`
(`renderer/lvgl/src/misc/lv_palette.c`) — both at the `LIGHT_COLOR_*` /
`DARK_COLOR_*` macros atop
`renderer/lvgl/src/themes/default/lv_theme_default.c` and at call sites spread
through its style init, so that macro list is not the whole set. **All three
are hardcoded Material tables indexed by palette row; there is no lightening
arithmetic to reproduce.** The two ramps are different WIDTHS (lighten takes
1..5, darken 1..4, so equal levels are unequal fractions of their ramp) and both
decrement the level before indexing — so a stock colour reconstructed by
formula, or by reading a macro's level argument as a 0-based index, lands a slot
off and looks entirely plausible. Know which way an out-of-range level fails
too: it warns and returns BLACK rather than refusing, so a wrong level is a
wrong colour and never an error.

Read the literal table. A copy of one that has to live outside `renderer/lvgl/`
carries the accessor and level that produced it, because nothing here
re-resolves such a copy when the vendored pin (`renderer/lvgl/.ported-from.edn`)
moves.

## Fix the contract, not the gate
- A red battery is the waist catching producer/interpreter drift. Fix the ROOT
  CAUSE (C source / theme / vocabulary), never suppress an oracle. Invariant
  exemptions are proof-carrying and ratchet DOWN only.
- Assets under `renderer/assets/` are generic and secret-free: the OFL font and
  the `images/demo/` twins of the vendored LVGL demo (source pinned via
  `renderer/lvgl/demos/.ported-from.edn`) carry provenance; icons and `test_*`
  fixtures are self-authored placeholders. Device-specific screens stay in the
  private consumers — this repo defines how interfaces work, not what any
  product's screens contain.

The `CLAUDE.md` charter states the "why"; the `devcards` rule covers the
goldens/gallery side, and the `renderer-gen` rule covers the fixture/codegen
seam under `tools/renderer-gen/`.
