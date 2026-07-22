# POC #2 — headless Rust + WASM framebuffer harness

**Status: ✅ proven end-to-end.** Our real pipeline renders headlessly: `EDN →
proto (.pb) → controls.wasm → Rust/wasmtime → RGBA8888 framebuffer → PNG`, no
GPU, no window.

## Provenance

This is **lifted verbatim** from the jettison_poc_webgpu_wasmtime POC's
`lvgl_harness` crate — which already drives our exact `controls_*` ABI. The only
change is a standalone `Cargo.toml` (dropped the workspace lint inheritance) so
it builds in-repo. It is the *verifier*; the sibling `jettison_view`
(wgpu+winit, on-screen) is the anti-pattern we avoid.

## Run

```sh
cd pocs/02-wasm-fb-harness && cargo build --release
./target/release/lvgl_harness \
  --wasm ../../output/controls.wasm \
  --pb   ../../output/ui/kitchen_sink.pb \
  --wasi-root ../../assets \
  --width 960 --height 540 --theme 0,1 --output snapshots --checkerboard
```

`--wasi-root` preopens the repo's canonical `assets/` tree (icons/fonts/images)
so `P:`-drive asset references resolve like on target. The visual-regression
suite runs in-container via `make harness-test` (builds the wasm + generated
fixtures first).

Produces `snapshots/kitchen_sink_bp0_{light,dark}.png` (960×540 RGBA). Verified
by eye: a full "Widget Gallery" — slider+bar, arc+spinner, red/green/amber
status LEDs, scale, toggles, spinbox — all correct.

## How it works (`src/wasm_host.rs`)

wasmtime 40, WASI-p1 reactor. Links `env.host_command` capturing the OPAQUE
cmd.* bytes the renderer relays (R5b cmd-out; tests prost-decode them) and
`env.host_report` capturing the OPAQUE `ui.WasmToHost{hover|cursor}` feedback
the renderer emits on a hover/cursor change (R5b HOST_REPORT; tests prost-decode
the hovered uid + cursor), calls `_initialize` → `controls_init(w,h)`, pushes UI
via `malloc → memory.write → controls_load_ui(ptr,len) → free`, ticks EXACTLY
`lvgl_harness::RENDER_TICKS` × `TICK_MS` (the pinned budget — deterministic
elapsed time, identical for both sides of a differential; anim-frozen EDN values
are functions of it), then reads `controls_get_framebuffer()` and slices `w*h*4`
bytes out of linear memory. `tests/visual_regression.rs` already has
`assert_identical` (byte-exact) + per-pixel diff helpers.

## The visual differential (landed)

The dual-oracle differential lives in `pocs/06-coverage-matrix/` (`run.sh`): an
independent `reference.wasm` (literal `lv_*` calls, built by `wasm.mk`) and our
EDN→proto→renderer module render the same cases; framebuffers bit-compare at
tolerance-0 and widget trees diff via RFC 6902 (`src/tree_diff.rs`). The
behavioral suite (`tests/visual_regression.rs`) runs in-container via `make
harness-test`.

Its capstone is the **demo-parity differential** (`make demo-parity` →
`tools/demo-parity.sh`): the real `lv_demo_widgets` (vendored, PRNG-seeded,
compiled into `reference.wasm`) vs the EDN recreation
(`edn/screens/demo_widgets.edn`), one render per tab at 960×540 — asserted
BIT-EQUAL by `src/bin/imgdiff.rs` (`--max-diff 0`; prints exact differing pixel
counts + clustered regions and writes red-overlay diff PNGs to
`output/demo-parity/` on divergence). Part of `make push-gates`.

## TODOs for productionization (per "latest everything")

- Bump `wasmtime`/`wasmtime-wasi` 40 → latest.
- For cross-module bit-compare add
  `Config::cranelift_nan_canonicalization(true)`
  + disable SIMD so floating-point rendering is bit-identical across modules.

