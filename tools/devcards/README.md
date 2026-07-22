# devcards — the public UI-contract proof venue

The devcard corpus runner: `tools/devcards/` beside the `renderer/`
interpreter tree, run by `renderer.mk fixtures` (locally and in CI). The
charter lives in the repo's `CLAUDE.md` § "The reference interpreter + the
devcards proof".

## What this is

protogen generates the language bindings of `ui_ast.proto` but, before this
tool, proved the contract only at the wire level (§9 golden byte vectors).
devcards completes the goldens concept at the render level — **proof from both
ends**:

1. **Schema end** — every fixture `.pb` validates against the schema.
2. **Pixel end** — the renderer (the repo's OWN `controls.wasm`, built by
   `renderer/wasm.mk`) renders each fixture to a raw RGBA framebuffer whose
   **hash** must match the committed golden manifest. Raw bytes, never
   encoded images — encoder-independent determinism: pinned wasm + pinned
   fixture + pinned tick count ⇒ bit-identical framebuffer.
3. **DOM end** — `dump_tree` invariants per card: no `clipped` / `overflow` /
   `text_clipped` / `text_truncated` / `offscreen` / `squished`; no zero-area
   node; no zero-VISIBLE-area node (`vis_px`); no unexpected host_command/report
   emissions.

## Committed vs transient artifacts

|                                       Artifact                                        |                       Status                       |
|---------------------------------------------------------------------------------------|----------------------------------------------------|
| fixture `.pb` + fixture source (EDN → public builder)                                 | committed                                          |
| golden manifest (per-card raw-framebuffer sha256 + wasm sha + tick count)             | committed                                          |
| **gallery JPEGs (high quality)** — 3 per widget: vanilla / asgard-dark / asgard-light | **committed** (online docs render from the repo)   |
| PNGs / raw dumps / diff overlays                                                      | transient (CI + local inspection only, gitignored) |

## The corpus (secret-free — §9's own rule, extended)

Generic widgets × state × size, kitchen-sink composites, and GENERIC meta-node
composition examples only. Device-specific meta nodes (DDE elements, camera
controls — proprietary interfaces) NEVER land here; private consumers run THIS
runner via their protogen submodule against their own private fixture corpora.

## Runner mechanism (`src/devcards/host.clj`)

GraalWasm, plain Maven deps (JDK 21+; interpreted on stock JDK, JIT under
GraalVM CE): ONE shared Engine + content-keyed Source cache (warm instantiation
~1-4ms), a FRESH Context per card (hermetic — no state bleed), the renderer's
four mandatory `env` imports captured, WASI assets preopen for fonts/icons, ABI
gate on start (`supported-abis`), raw-framebuffer read + dump_tree copy-out.
