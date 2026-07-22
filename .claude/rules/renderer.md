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
  target in `renderer.mk`; CI runs them in `.github/workflows/renderer.yml`.
- CI's fixtures/gallery jobs consume an ALREADY-BUILT `controls.wasm` (the
  `*-prebuilt` targets, guarded by `wasm-present`) — a missing wasm is a
  sequencing bug, never a skip.

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
goldens/gallery side.
