# Renderer C refactor — shape map, helper design, and what each commit proved

Owner: `claude-crefactor`. Task 400. Base: `cfb55e2a` (seed commit; code base
`fb2d339b`). Six commits. Working tree clean. `make -f renderer.mk
check-renderer` GREEN at the tip (all 29 lanes); `make -f lint.mk lint-c-tidy`
green over all 9 first-party files; `make -f lint.mk fmt-c` clean.

The previous FINAL_REPORT.md at this path belonged to an earlier fork task
(`gate-port`, task 57) and rode in with the clone's history; this file replaces
it per the brief's return contract, which names this path as the deliverable.

## 0. Verification discipline — what was RUN vs READ

Every green below names the command that produced it. The battery entry is
`tools/uber.sh make -f renderer.mk <lanes>` (the pinned toolchain image; the
host has no WASI-SDK). Baseline before any edit: a from-scratch build (543
compiles, warning-free under `-Wall -Wextra -Werror`) and a full
`check-renderer` — GREEN, coverage matrix 92 matched / 0 diverged, demo-parity
3 of 3 tabs bit-equal.

Per-commit lanes were chosen for the surface touched (stated per commit
below); the STACK TIP re-ran the full `check-renderer`: GREEN across
graal-check, generated-projection(+canary), construct-bindings,
conventions-projection, state-mirror, manifests, devcards-test,
scratchcard-test, scratchcard-brief, clj-schema-test, spec-coverage,
standard-brief-generate, wasm, wasm-inputs-check, reference, dead-c-externs(+
test), fixtures, deadzone-canary, overlap-canary, scratchcard-lane,
dump-contracts, harness, interaction, oracles, reload, decode-limits,
wire-constraints, presence-semantics — with coverage matrix 92/0 and
demo-parity 3/3 bit-equal at the tip.

Byte-identity is the acceptance for the pure-refactor commits, and it is what
the pixel lanes assert: the goldens hash raw framebuffers, demo-parity and the
matrix pixel-compare two independent implementations, and the cross-engine
mirror (`composition_cross_engine_fb`) re-proved wasmtime ≡ GraalWasm at the
tip. No golden, no matrix fixture and no parity tab moved at any commit.

## 1. Text anchors verified BEFORE the design (what a refactor here may not move)

The C source is load-bearing TEXT for several gates; all were enumerated
before any edit and none was disturbed:

- `ui-ast-constraints` `:guard` tokens that must occur in
  `renderer/src/renderer.c`: `cmd_patch_orient_y`, `CMD_PATCH_MAX_BY_VALUE`,
  `event_trigger_defined`, `gesture_delta_sign_ok`, `MAX_TARGET_BORDER_WIDTH`,
  `MAX_TARGET_BOXES`, the literal log string `unknown patch op kind`,
  `compare_op_ok`, `CMD_PATCH_MAX_GESTURES`, `MAX_HIT_SLOP`,
  `widget_type_defined`.
- The opa lane's carve-out canary reads `renderer.c` as text: the comment
  sentence `WIRING lv_table_set_cell_value HERE`, the code token
  `ui_WidgetNode_table_props_tag`, and the ABSENCE of a
  `lv_table_set_cell_value` call.
- The palette suite pins three `main.c` signatures verbatim (each body
  containing `palette_observer_clear()`), and several `palette_observer.c`
  strings.
- Every `MAX_*` define is swept by the renderer-caps manifest emit
  (counted-or-allowlisted, both directions) — see commit 6 for the measured
  refusal.
- The WASM ABI (every exported symbol name and signature) and the `dump_obj`
  key vocabulary are frozen contracts; neither was touched.

## 2. The shape map (counted, not guessed)

First-party surface read whole: `renderer/src/*.c|*.h` (13,982 lines excluding
the eight `lv_font_conv`-generated `font_*.c` tables), `renderer/lv_conf.h`,
and the hand-authored tool C (`theme-style-groups/emit.c`). Repetition found:

1. **`apply_style_property`** — 823 lines, 107 arms; 101 of them one of five
   shapes (46 int / 19 opa-uint / 14 enum-cast-uint / 8 int32-cast-uint /
   5 bool-uint / 9 color), 6 genuinely irregular (BG_COLOR's ride-along opa,
   the two string-pool arms, the SHADOW bundle, MARGIN_ALL's fan-out,
   FLEX_FLOW's LUT guard).
2. **The compare-binding quadruplet** — `apply_visibility` /
   `apply_checked_when` / `apply_enabled_when` / `apply_pending_when` plus
   four observer callbacks: 231 lines differing only in target bit (HIDDEN
   flag / CHECKED / DISABLED / USER_1), polarity, and diagnostic name.
3. **The pending-attachment drain** — the eight apply loops + nine queue
   resets + tabview activation duplicated verbatim between the full build and
   the patch batch end; the queue-reset preamble duplicated besides.
4. **`widget_ctx_t` init + callback wiring** — spelled by hand at the three
   decode entry points (streamed child, full-build root, patch op node),
   ~20 lines each.
5. **Queue-overflow pushes** — seven sites with the same
   LOG_ERROR-and-latch guard, only the queue name varying.
6. **theme.c token pairs** — `lv_color_hex(pick_u32(t->dark, THEME_X_DARK,
   THEME_X_LIGHT))` at 32 sites.
7. **The dropdown value-map bound** — `values[16]` restating the wire's
   `max_count:16` plus a SILENT `>16` clamp over it (the one defect-shaped
   finding; commit 6).

What was deliberately NOT treated as repetition: `reference_ui.c` (the
independent oracle — sharing helpers with `renderer.c` would defeat the
differential); `gesture.c` (a deliberately literal 1:1 port of the host-side
recognizer); `theme_apply`'s per-class arms (each carries distinct measured
rationale); `dump_obj` (the dump vocabulary's one home, sitting at the
clang-tidy size ratchet); the registry linear-scan/swap-remove idiom (6–8
lines per site, a generic container would obscure more than it saves).

## 3. The helper design, and what each helper collapsed

- `reset_pending_queues()` + `drain_pending_attachments()` — collapse (3):
  two verbatim copies of an ORDERED contract into one home each. 87 lines →
  58 (net −29), and a new queue now has two wiring points instead of four.
- `compare_binding_class_t` (four static descriptors) +
  `apply_compare_binding()` + one observer + one target set/clear helper —
  collapse (2): 231 lines of quadruplicate into one applier; the EQ/NOT_EQ
  native-bind fast path reduces to `bind_if_eq = eq_holds ==
  assert_when_holds`, checked against each retired arm. Net −128. A fifth
  reactive binding is now a descriptor line plus a queue.
- `widget_ctx_init()` (+ four callback forward declarations) and
  `pending_queue_has_room()` — collapse (4) and (5). Net −39. A
  `widget_ctx_t` field is initialized in one place; previously a field added
  to one init site and not the others was stack garbage no compiler flags.
- Six X-macro row tables + one case-generator per shape — collapse (1):
  823 → 233 lines (net −590). The rows were extracted from the old switch
  MECHANICALLY (scripted parse, counts asserted per family), never retyped. A
  new regular StyleProperty is one row in the table matching its oneof slot.
  The one lesson worth keeping: the table parameter must not be named `X`,
  because `PROP_X`/`PROP_Y` are rows and macro-parameter capture expands
  `X(X, x)` into itself — the compiler caught it on the first build.
- `mode_hex(t, dark_hex, light_hex)` — collapse (6): 32 sites rewritten by a
  regex over the exact compound (count asserted at 32). The five
  `pick_color(t->dark, ...)` sites select between expressions, not hex pairs,
  and stay.

Totals: `renderer.c` 6335 → 5564 lines, `theme.c` 1628 → 1606; stack diff
+551/−1344 (net −793) with zero pixels moved.

## 4. Defect found, red-first evidence

**The dropdown value-map's silent clamp** (commit `426ec8e8`).
`register_dropdown_value_map` clamped `count` to a bare `16` — a second copy
of `ui.DropdownProps.option_values`' `max_count:16`. Unreachable today (nanopb
refuses a 17th element at decode), but the day the wire widens without this
map moving, the clamp silently truncates every wide enum dropdown: dropped
values never select, which reads as a device ignoring its own state.

Red-first: an intermediate `_Static_assert` form was driven RED — the
constant flipped to 17 fails the build with the assert's own message naming
the requirement — and the landed form goes further per the no-second-copy
rule: the array width is now DERIVED from the generated struct
(`DROPDOWN_VALUE_MAP_WIDTH` = sizeof the generated array), so there is
nothing left to assert or clamp and a widened wire widens the map in the same
regeneration.

Measured along the way: the renderer-caps manifest emit REFUSES an
unaccounted `MAX_*` define (a first draft introduced `MAX_DROPDOWN_VALUE_OPTIONS`
and the manifests lane failed with "neither counted nor allowlisted" naming
it) — the completeness sweep works, and the derived-width form is what makes
a new cap unnecessary rather than evaded: the bound's one home is the wire.

## 5. Per-commit record (sha — subject — proof)

1. `eb507c63` — refactor(renderer): one home for the pending-queue reset and
   batch drain. Lanes: wasm+reference+fixtures+oracles — matrix 92/0,
   demo-parity 3/3 bit-equal, morph-parity 16/16, fixtures 0 findings; 1
   object recompiled.
2. `f7adbc04` — refactor(renderer): collapse the compare-state bindings into
   one applier. Lanes: wasm+reference+harness+fixtures —
   visual_regression 281 passed / 0 failed, fixtures 0 findings.
3. `9df87ac2` — refactor(renderer): one home for widget-ctx init and
   queue-room guard. Lanes: wasm+reference+harness+fixtures — 281/0,
   fixtures 0 findings.
4. `d13dec98` — refactor(renderer): drive the regular style-prop arms from
   row tables. Lanes: wasm+reference+harness+fixtures+oracles — matrix 92/0,
   demo-parity 3/3 bit-equal, morph-parity 16/16, 281/0, fixtures 0 findings.
5. `7a0e9499` — refactor(theme): name the dark/light token-pair select.
   Lanes: wasm+reference+clj-schema-test+fixtures+oracles — renderer-gen
   suite 178 tests / 4397 assertions / 0 failures, matrix 92/0, demo-parity
   3/3 bit-equal. The regenerated `theme-style-groups.json` (its producer is
   in-lane; the manifest is not in the brief's FORBIDDEN list) moved ONLY its
   recorded `theme.c` input hash — the projected style data from the
   compiled execution of `theme.c` is byte-identical, which is the
   value-neutrality proof across every family/mode/size tier.
6. `426ec8e8` — fix(renderer): derive the dropdown value-map width from the
   wire struct. Lanes: manifests+harness+fixtures — manifests emit clean,
   harness 0 failed, fixtures 0 findings; the red-first assert witnessed as
   described in §4.

Stack tip: full `check-renderer` GREEN (all lanes, §0) and `lint-c-tidy`
green (9/9 files, WarningsAsErrors: '*').

## 6. What was NOT done, and why — the follow-ups this lane could not take

- **Four prose sites outside this lane still name the retired binding
  functions** (`apply_visibility` / `apply_checked_when` /
  `apply_pending_when`): `tools/renderer-gen/src/lvgl_codegen/schema.clj:448`,
  `tools/renderer-gen/src/lvgl_codegen/patch.clj:37`,
  `tools/renderer-gen/test/lvgl_codegen/schema_test.clj:148`,
  `renderer/wasm_harness/tests/visual_regression.rs:3048`. The mechanisms
  they describe are unchanged (the deferred post-subjects pass; native bind
  vs observer); only the names went stale. All four files are outside FILES
  YOU OWN, so they are recorded here rather than edited. The rename target is
  `apply_compare_binding`.
- **The clang-tidy function-size ratchet can now move DOWN** and
  `renderer/.clang-tidy` is not in this lane. Its thresholds are seeded at
  the tree's former maxima (LineThreshold 821 etc., the config names the
  measurement recipe); after this stack the worst function's source span is
  far smaller. Re-derive per axis with the config's own recipe and ratchet in
  a follow-up — the config states they only ever move down.
- **`svg_decoder_init` does not check `lv_image_decoder_create` /
  `tvg_engine_init` results.** Judged not worth a change: on this target an
  init-time failure traps loudly on first use (wasm null-deref traps redden
  every lane), so the fail-fast property already holds operationally; noted
  so the next reader does not re-derive the question.
- **`main.c` was left alone by design**: it is the ABI home, the dump
  vocabulary's one home, and pinned by text-reading tests; its length is
  contract surface, not repetition.

## 7. Where the brief was right, and where its emphasis missed

The brief's premise — "the interpreter has grown by accretion of widget arms;
the repetition between arms is the primary refactor target" — was HALF right.
The per-widget arms (`ensure_widget`, `apply_widget_props`) turned out to be
already lean: each arm is genuinely distinct, most carry load-bearing
per-widget rationale, and collapsing them would trade real information for
line count. The repetition that actually paid was one level down (the style
PROP arms, 101 of 107 mechanical) and one level up (the binding families and
the decode plumbing, duplicated whole). The counted map in §2 is the evidence;
nothing in the widget-arm switches was worth a helper beyond what already
exists.
