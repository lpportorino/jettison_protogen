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
- **protogen's own gate runs THROUGH the registry**, so the instruction above
  is not advice this repo exempts itself from: `core.clj` judges every card via
  `findings/card-findings`. The two lanes it passes live in `devcards.lanes` —
  `atomic-findings` (the `:expect` routing, including the INVERTED
  `:probe-defect` arm) and `composition-findings` (the `:tree` builtin plus
  `findings/emission-by-mode-producer`). They live there rather than in
  `core.clj` for a testability reason worth keeping: core loads the generated
  bindings, so nothing requiring it runs under the `:test` alias, and a lane
  that cannot be named in a test cannot be pinned by one.
- Select a builtin with `findings/builtin-producer`, never by position —
  `(first builtin-producers)` silently repoints the lane when that vector is
  reordered or grown.
- **`builtin-producers` is `card-findings`'s DEFAULT, not protogen's armed
  set.** Both statements matter and they pull opposite ways, so measure rather
  than reason: a caller that OMITS `:producers` runs every entry of that
  vector, so appending to it does arm the new rule for that caller. Every lane
  in `devcards.lanes` NAMES its producers, so appending arms nothing in
  protogen's own gate — verified by appending an always-firing producer and
  watching the naming caller stay silent while the omitting caller reported it.
  Arming a rule here means adding it to a lane's producer vector, and that is a
  change owing its own evidence.

## The VLM UI review — one batched agent, briefed once, never a gate

Running it before a push that changes what a card renders is MANDATORY, in this
repo and in every repo deriving from this UI (see `CLAUDE.md` §"Consuming the UI
standard" for the obligation and the disposition rule). This section is how.

- **Reuse the artifacts that exist; add no pipeline.** The inputs are the
  committed gallery images under `docs/widgets/<WIDGET>/` (one per family — see
  `gallery.clj` `family-renders`) and the per-card `dump_tree` the runner already
  captures (`core.clj` `render-one!`). Anything the review WRITES goes through
  `docgen.clj` (`do-not-edit-header`, `md-table`, `image-grid-md`,
  `write-text!`), so it carries the DO-NOT-EDIT header like every other
  generated doc.
- **BATCHED — one agent over a large batch, explicitly not one agent per
  check.** Loading the standard is the expensive part, so it is paid ONCE and
  amortised across the batch; fanning out per element re-pays it every time and
  leaves each agent judging a render in isolation, with no sight of the same
  widget across families and states — which is exactly where the defects are
  visible.
- **The briefing is GENERATED from the canonical sources**, never hand-written
  prose about them: the contract text, the live classification table
  (`lvgl_classes.clj`), and each producer's declared `:thresholds`. Regenerate it
  when any of those move — a stale briefing has the model gating on a number the
  registry would reject as an unknown threshold key.
- **Emit the producer shape** — `{:card :invariant :node :detail}`, `:invariant`
  a keyword naming the clause, as in `devcards.findings`. That is what lets a
  finding be exempted, or retired, by the machinery already here.
- **Do not wire it into the verdict.** It does not join `:expect`, does not
  contribute to the fixtures exit code, and does not gate CI: the lanes above are
  reproducible and this one is not. A finding that recurs deterministically is a
  SPEC for a producer — arm it through the registry per §"Adding a rule", and
  the gate then belongs to the producer, not to the model.

## Secret-free — gate-enforced
- Generic widgets, compositions, and generic meta-node examples only.
  Proprietary device meta-nodes (DDE, camera controls) NEVER land here; private
  consumers run THIS runner via their protogen pin against their own private
  corpora.
- `corpus-secret-findings` (`gates.clj`) scans EVERY card population — prose
  included, since the corpus file IS the public artifact — and rides the normal
  verdict, so a hit exits non-zero. Widening its match classes is a deliberate
  change that owes its own false-positive measurement.
