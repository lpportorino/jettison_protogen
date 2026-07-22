# Headless Rust + wasmtime framebuffer harness

The wasmtime host that drives protogen's own `controls.wasm` reference
interpreter headlessly: `EDN → proto (.pb) → controls.wasm → wasmtime →
RGBA8888 framebuffer → PNG`, no GPU, no window. It is the wasmtime *verifier*
half of the renderer battery — the engine the GraalWasm devcards runner
(`tools/devcards/`) is cross-checked against: both hosts run the same pinned
render protocol and byte-compare raw framebuffers. Port lineage is pinned in
`.ported-from.edn`.

## Where it runs

From the repo root, inside the `Dockerfile.base` toolchain container (the host
has no WASI-SDK):

```sh
make -f renderer.mk harness         # regen fixtures (tools/renderer-gen) → cargo test --test visual_regression
make -f renderer.mk interaction     # the cross-engine mirror suite (after `make -f renderer.mk fixtures`)
make -f renderer.mk morph-parity    # the tree-patch dual oracle
make -f renderer.mk check-renderer  # the whole battery
```

`harness` regenerates the fixtures first (`tools/renderer-gen` →
`renderer/output/fixtures/*.pb` + `renderer/output/ui/tabview_demo.pb`), so the
suite never runs against stale inputs.

## Manual snapshot run

The `lvgl_harness` binary renders one fixture to PNG (its built-in
`--pb`/`--output` defaults point elsewhere — always pass them):

```sh
cd renderer/wasm_harness && cargo build --release
./target/release/lvgl_harness \
  --wasm ../output/controls.wasm \
  --pb   ../output/ui/tabview_demo.pb \
  --wasi-root ../assets \
  --width 960 --height 540 --theme 0,1 --output snapshots --checkerboard
```

`--wasi-root` preopens the canonical `renderer/assets/` tree (icons/fonts/
images) so `P:`-drive asset references resolve like on target. `--pb` renders
any generated fixture (`renderer/output/fixtures/*.pb`, `renderer/output/ui/*.pb`);
source screens are `renderer/edn/screens/*.edn`. A few reference snapshots are
committed under `snapshots/` for eyeballing.

## How it works (`src/wasm_host.rs`)

wasmtime, WASI-p1 reactor (versions pinned in `Cargo.toml`). Links the
renderer's mandatory `env` imports (`renderer/src/host_imports.h`) — `host_command`
(opaque `cmd.*` bytes; tests prost-decode), `host_report` (`ui.WasmToHost`
hover/cursor feedback), `host_event` (the named-event JSON envelope),
`host_proxy_report` (positioning-proxy geometry) — calls `_initialize` →
`controls_init(w,h)`, pushes UI via
`malloc → memory.write → controls_load_ui(ptr,len) → free`, ticks the pinned
budget (`lvgl_harness::RENDER_TICKS` × `TICK_MS` — deterministic elapsed time,
identical for both sides of a differential), then reads
`controls_get_framebuffer()` and slices `w*h*4` RGBA bytes out of linear memory.
Straight (non-premultiplied) alpha: consumers must composite (see
`src/framebuffer.rs` `composite_on_checkerboard`).

## The test suites (`tests/`)

- `visual_regression.rs` — the behavioral suite (`make -f renderer.mk harness`):
  per-widget non-empty frames, class reorder invariance (identical output) and
  modify (output changes), value changes, theme light/dark, fonts/icons via the
  WASI preopen, the `host_event` envelope lane. Ships `assert_identical`
  (byte-exact) + per-pixel diff helpers.
- `composition_interaction.rs` — the cross-engine mirror: re-renders the SAME
  card bytes the GraalWasm devcards runner persisted under
  `tools/devcards/out/composition/`, byte-compares the raw framebuffers, and
  replays the pointer contract (press-seek / drag / ext-click envelope /
  dock-fold). `make -f renderer.mk interaction` (needs `fixtures` first).
- `morph_parity.rs` — dual-oracle tree-patch parity: `load_ui(base) +
  apply_patch` vs a fresh `load_ui(target)`, `dump_tree` byte-equal +
  framebuffer pixel-identical, plus state-preservation and failure contracts.
- `fb_hash_probe.rs` — the cross-engine determinism probe (writes raw-FB twins to
  `target/fb-probe/` for external `sha256sum` comparison against the GraalWasm
  dumps).

## The visual differential

The dual-oracle differential lives in `renderer/coverage_matrix/` (`run.sh`,
`make -f renderer.mk matrix`): an independent `reference.wasm` (literal `lv_*`
calls, built by `wasm.mk`) and the EDN→proto→`controls.wasm` path render the
same cases; framebuffers bit-compare at tolerance-0 and widget trees diff via
RFC 6902 (`src/tree_diff.rs`). Its capstone is the demo-parity differential
(`make -f renderer.mk demo-parity`): the vendored PRNG-seeded `lv_demo_widgets`
(compiled into `reference.wasm`) vs the EDN recreation
(`renderer/edn/screens/demo_widgets.edn`), one render per tab, asserted BIT-EQUAL
by `src/bin/imgdiff.rs` (`--max-diff 0`; writes red-overlay diff PNGs to
`output/demo-parity/` on divergence).
