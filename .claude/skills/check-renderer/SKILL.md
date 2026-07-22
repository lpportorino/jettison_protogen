---
name: check-renderer
description: Run the renderer proof battery (make -f renderer.mk check-renderer) — build the wasm oracles, then goldens + wasmtime harness + cross-engine mirror + morph/matrix/demo oracles. Use to verify a renderer or devcards change before it lands.
argument-hint: "[lane]"
disable-model-invocation: true
allowed-tools: Bash
---

# Run the renderer battery

The reference-interpreter proof battery. Runs from the repo root **inside the
toolchain container built from `Dockerfile.base`** — the host has no WASI-SDK,
so a local `make` outside the container will fail the wasm build.

**If an argument was given**, run just that lane:

```bash
make -f renderer.mk $ARGUMENTS
```

Any target in `renderer.mk`'s `.PHONY` list is a valid lane — see "What each
lane proves" below for the battery phases; `fixtures-prebuilt` / `gallery-prebuilt`
are the CI prebuilt-wasm variants.

**Otherwise**, run the full battery:

```bash
make -f renderer.mk check-renderer
```

## What each lane proves
- `wasm` / `reference` — build `controls.wasm` (shipped) + `reference.wasm` (the
  independent literal-`lv_*` diff oracle).
- `fixtures` — devcards corpus: schema-validate + RAW-framebuffer goldens + DOM
  invariants; re-mints `tools/devcards/goldens/manifest-*.edn`.
- `harness` — wasmtime `visual_regression` over freshly regenerated fixtures.
- `interaction` — cross-engine mirror: wasmtime re-renders the SAME bytes the
  GraalWasm devcards runner built, byte-compares the framebuffers, and replays
  the pointer contract (needs `fixtures` to have run first).
- `oracles` — `morph-parity` (tree-patch vs full reload) + `matrix` (dual-oracle
  (property, value) rows) + `demo-parity` (`lv_demo_widgets` bit-equal per tab).

## Triage a red battery
- Red goldens or gallery: a pixel-shifting change must re-mint BOTH — run
  `fixtures` then `gallery-prebuilt` and commit `tools/devcards/goldens` +
  `tools/devcards/docs` together (CI diffs both).
- Any red oracle: fix the ROOT CAUSE in the C source / theme / vocabulary —
  never suppress the gate. See the `renderer` and `devcards` rules for the
  contract.
