# devcards — the public UI-contract proof venue

The devcard corpus runner: `tools/devcards/` beside the `renderer/`
interpreter tree, run by `make -f renderer.mk fixtures` locally (or
`fixtures-prebuilt` in CI, which reuses the battery's already-built
`controls.wasm`). The
charter lives in the repo's `CLAUDE.md` § "The reference interpreter + the
devcards proof".

## What this is

protogen generates the language bindings of `ui_ast.proto` and proves the
contract at the wire level (§9 golden byte vectors); devcards adds render-level
proof — **proof from three ends**:

1. **Schema end** — every fixture `.pb` validates against the schema.
2. **Pixel end** — the renderer (the repo's OWN `controls.wasm`, built by
   `renderer/wasm.mk`) renders each fixture to a raw RGBA framebuffer whose
   **hash** must match the committed golden manifest. Raw bytes, never
   encoded images — encoder-independent determinism: pinned wasm + pinned
   fixture + pinned tick count ⇒ bit-identical framebuffer.
3. **DOM end** — `dump_tree` invariants per card: no layout defect flag (the
   `defect-flags` set in `invariants.clj`); no zero-area node; no
   zero-VISIBLE-area node (`vis_px`); no unexpected host_command/report emissions.

## Committed vs transient artifacts

|                                       Artifact                                        |                       Status                       |
|---------------------------------------------------------------------------------------|----------------------------------------------------|
| fixture `.pb` + fixture source (EDN → public builder)                                 | committed                                          |
| golden manifest (per-card raw-framebuffer sha256 + the render protocol it was minted under) | committed                                          |
| **gallery JPEGs (high quality)** — the render set in `gallery.clj` `family-renders` (3 sheets: vanilla + asgard dark/light) | **committed** (online docs render from the repo)   |
| PNGs / raw dumps / diff overlays                                                      | transient (CI + local inspection only, gitignored) |

## The corpus (secret-free — §9's own rule, extended)

Generic widgets × state × size, kitchen-sink composites, and GENERIC meta-node
composition examples only. Device-specific meta nodes (DDE elements, camera
controls — proprietary interfaces) NEVER land here; private consumers run THIS
runner via their protogen pin against their own private fixture corpora.

## Runner mechanism (`src/devcards/host.clj`)

GraalWasm, plain Maven deps — but GraalVM CE is REQUIRED, not merely preferred:
a stock JDK has no JVMCI/Graal compiler, so the polyglot host would interpret
the wasm, and the runner hard-fails rather than degrade silently to that.
ONE shared Engine + content-keyed Source cache (warm instantiation
~1-4ms), a FRESH Context per card (hermetic — no state bleed), the renderer's
four mandatory `env` imports captured, WASI assets preopen for fonts/icons, ABI
gate on start (`supported-abis`), raw-framebuffer read + dump_tree copy-out.
