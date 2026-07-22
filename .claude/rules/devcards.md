---
description: Working rules for the devcards corpus runner — golden manifests, DOM/emission invariants, and the committed JPEG gallery. Loads when editing tools/devcards.
paths:
  - "tools/devcards/**"
---
<!-- LOAD-TEST: devcards -->

# devcards — working rules

The devcard corpus proves the `ui_ast` schema and the renderer agree, from three
ends: schema-validate every fixture, hash the RAW framebuffer (goldens), and
assert `dump_tree` invariants. One CLI (`devcards.core`): `generate` (build +
render + judge + write goldens) and `gallery` (write the committed JPEG doc
tree). Run via `make -f renderer.mk fixtures` (`*-prebuilt` in CI).

## Goldens hash RAW framebuffers — the JPEGs are presentation only
- `goldens/manifest-*.edn` = per-card sha256 over the RAW RGBA bytes read out of
  wasm linear memory (encoder- and engine-independent, so one manifest is
  authoritative for both the GraalWasm and wasmtime hosts). NEVER hash an encoded
  image. Each manifest pins the `:protocol` it was minted under.
- The gallery JPEGs under `docs/widgets/` (one per committed theme family — see
  `gallery.clj` `family-renders`) are the ONLY committed images:
  flatten-before-encode (the straight-alpha garbage under A=0 must be composited
  onto black first) at the pinned quality (`jpeg.clj` `default-quality`). PNGs,
  raw dumps, and diff overlays stay gitignored.

## Re-mint goldens AND docs together — CI diffs both
- A renderer or schema change that shifts any pixel MUST re-run BOTH
  `make -f renderer.mk fixtures` (re-mints `goldens/`) and `gallery-prebuilt`
  (re-mints `docs/widgets/`), committed in the SAME change. CI enforces it with
  `git diff --exit-code tools/devcards/goldens tools/devcards/docs`.
- `docs/widgets/**` is GENERATED, DO-NOT-EDIT. Edit `corpus/spec.edn`,
  `corpus/composition.edn`, the conventions manifests, or the generator ns —
  never the output.

## The gate lanes (`corpus/spec.edn` `:expect`)
- coverage · state-contract (`:distinct` hash ≠ its `default` baseline; `:inert`
  hash == baseline) · vanilla≡stock (theme family equality) · inert-prop
  (composition lane: an interaction-only prop hashes IDENTICAL to its
  `:base-card`, proving it moves zero pixels).
- Cards are UNSTYLED — everything unset falls through to the loaded wasm theme,
  the object under test. A red devcard gate after a SCHEMA change is the waist
  catching producer/interpreter drift: fix the contract, not the gate.

## Secret-free, GATE-HELD (secret-scan in CI)
- Generic widgets, compositions, and generic meta-node examples only.
  Proprietary device meta-nodes (DDE, camera controls) NEVER land here; private
  consumers run THIS runner via their protogen pin against their own private
  corpora.
