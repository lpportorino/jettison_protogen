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

## Adding a rule — through the registry, never by editing a lane
- `devcards.findings` is the finding-producer registry: a producer is
  `{:id :fn :requires :thresholds}` and `:fn` takes ONE context map, never a bare
  tree. That signature is load-bearing — a rule like the layer contract needs the
  tree, the consumer's DECLARATION and the compositor's proxy rects together
  (host-proxy stacking happens after LVGL finishes, so it is not readable from
  child order), plus ancestry, which the registry precomputes once via
  `invariants/annotate-tree` and shares.
- The registry refuses these silences, each because its output would otherwise
  be byte-identical to a clean run: an empty producer set; a `:requires` key the
  caller never SUPPLIED (supplied-but-empty is a claim, absent is an oversight,
  and `nil` counts as absent while `false` stays a claim); a caller-supplied
  `:nodes`, which is registry-DERIVED; an unknown threshold key; and two
  producers colliding on one threshold key.
- **Producers declare every input they READ**, with no defaults. A defaulted
  input silently weakens the lane that reads it — `(or caps {:vis-px? false})`
  once deleted the whole `:zero-visible-area` class without a word.
- Classification (`devcards.classify`) is the consumer's table, and an
  undeclared widget type is an `:unclassified-type` FINDING, not a skip.
  `devcards.lvgl-classes/merge-consumer` is the starter table to build on.
- New rules ship OPT-IN — neither `overlap/producer` nor `layers/producer` is in
  `builtin-producers`. Arming one against protogen's own corpus is a separate
  change owing its own evidence.
- **A pairwise geometry rule must enumerate pairs in pre-order and never use
  vector `compare` for paint order** — Clojure's vector comparator is
  COUNT-first, so it inverts the verdict whenever the earlier node is deeper.
  That shipped once and no test caught it, because the suite only had
  equal-depth pairs.
- **protogen's OWN gate is not yet routed through the registry.** `core.clj`
  still composes `tree-findings` + `emission-findings` by hand, with `:expect`
  routing and a doubled emission lane that `builtin-producers` does not express.
  So the registry is available to consumers, and adding a producer does NOT by
  itself make `make -f renderer.mk fixtures` run it — arming a rule against this
  repo's corpus additionally means wiring `core.clj`. Membership in
  `builtin-producers` is not the armed state either; nothing here reads it yet.

## Secret-free — gate-enforced
- Generic widgets, compositions, and generic meta-node examples only.
  Proprietary device meta-nodes (DDE, camera controls) NEVER land here; private
  consumers run THIS runner via their protogen pin against their own private
  corpora.
- `corpus-secret-findings` (`gates.clj`) scans EVERY card population — prose
  included, since the corpus file IS the public artifact — and rides the normal
  verdict, so a hit exits non-zero. Widening its match classes is a deliberate
  change that owes its own false-positive measurement.
