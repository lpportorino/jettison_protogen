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
render + judge + VERIFY every hash against the committed manifests, then re-mint
them) and `gallery` (write the committed JPEG doc tree). Run via
`make -f renderer.mk fixtures` (`*-prebuilt` in CI).

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
- **The first `fixtures` run after a deliberate pixel change is SUPPOSED to be
  red, and that is not a bug to work around.** The lane verifies against the
  committed manifests BEFORE re-minting them, so it names every card whose hash
  moved and exits non-zero — with the corrected manifests already written to the
  tree. Read the findings, confirm the movement is the one you intended, commit
  the manifests, re-run green. Same shape as `manifests` and
  `generated-projection`. What you must never do is reach for the re-mint
  without reading what moved: the whole point is that 243 cards shifting used to
  print GREEN.
- **`docs/` has TWO gates that catch opposite failures, and NEITHER is in the
  battery entry.** The `gallery` arm runs a two-way disk audit over
  `devcards.docs/audit-root` and exits through `lanes/run-verdict`, so a file
  the generators no longer emit but that is still TRACKED is caught as an
  orphan. **It is a DISK audit, so an UNTRACKED file is equally in scope** —
  `devcards.docs-test`'s recogniser walk reds on a gitignored leftover (a
  retired debug `.png` beside the JPEG gallery) while `git ls-files` reports
  clean. Read a failure there as "something is on disk that the emitter never
  wrote", never as "a committed artifact is unrecognised": taking it to git
  finds nothing and the test looks broken when it is working. What that
  audit CANNOT see is changed CONTENT — a re-minted sheet is
  emitted and present, so the audit is satisfied — and that half is CI's
  `git diff --exit-code`. One gate is for retired artifacts, the other for
  moved bytes; neither substitutes for the other.
- **`check-renderer` runs neither of them**, because it does not list
  `gallery-prebuilt`. A green local battery therefore says NOTHING about
  `docs/` in either direction — not that the sheets are fresh, and not that no
  orphan is shipping. Run `gallery-prebuilt` yourself to reach the orphan half.
  The content half has no make TARGET — it exists only as a workflow step, and
  cannot be a battery lane because git cannot resolve this checkout inside the
  container — but you reach the same assertion by hand, and doing so before a
  push is the operator's job: re-mint in the PINNED CONTAINER
  (`tools/uber.sh 'make -f renderer.mk gallery-prebuilt'`; a host run rewrites
  every sheet through a different JPEG encoder) and then run
  `git diff --exit-code tools/devcards/docs` on the host. That two-step is this
  repo's normal local method, not a workaround — `.claude/rules/uber-container.md`
  records a full gallery re-mint from that container passing CI's own step
  unchanged. The golden verify half is separate and does ride `check-renderer`,
  but it covers `goldens/` only.
- `docs/widgets/**` is GENERATED, DO-NOT-EDIT. Edit `corpus/spec.edn`,
  `corpus/composition.edn`, the conventions manifests, or the generator ns —
  never the output.

## The gate lanes (`corpus/spec.edn` `:expect`)
- coverage · state-contract (`:distinct` hash ≠ its `default` baseline; `:inert`
  hash == baseline) · vanilla≡stock (theme family equality, PLUS absolute
  stock manifests — equality alone is blind to a change moving both families
  together, and family 1 is deliberately unpinned because a vanilla drift
  already implies an equality or stock red) · inert-prop
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
- New rules ship OPT-IN: neither `overlap/producer` nor `layers/producer` is in
  `builtin-producers`, and arming one against protogen's own corpus is a
  separate change owing its own evidence.
- **`overlap/producer` IS armed here** — `devcards.lanes` passes it on both the
  atomic and composition lanes, with the shipped starter table and
  `:overlap/gap-px 0`, at zero findings. Getting there took the interpreter
  DECLARING its own proxy composition (`proxy_root` / `proxy_part` /
  `proxy_owner` in the dump) and clearing CLICKABLE on two decorative widgets
  that were sitting in the pointer path. Neither was a silencing, and the
  exclusion's narrowness is canaried three ways.
- **`layers/producer` is NOT armed, and cannot be** for the nodes that matter.
  Its declaration is uid-keyed, and the renderer's own affordances never pass
  through `finalize_widget`, so they are uid-free in this repo AND in every
  consumer. Measured three ways — no declaration, a declared z, a declared z
  plus a proxy rect — it returns byte-identical output. Do not spend another
  session rediscovering this; a consumer's AUTHORED nodes do carry uids, so the
  lane is usable there, on those.
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

Running it before a push that changes what a ui_ast SURFACE renders is
MANDATORY — here, and wherever such a surface is authored, owed for the SURFACE
and not for the repository (see `CLAUDE.md` §"Consuming the UI standard" for the
obligation and the disposition rule). This section is how.

- **RESOLVE THE INPUTS FIRST, with `preflight.sh`, and never by globbing.**
  `.claude/skills/ui-standard-review/preflight.sh`, run from the surface's own
  repo root, prints the unit roster that IS the batch — or refuses, with a
  distinct exit code per reason: no gallery (3), a gallery that resolved inside
  the PIN (4), a gallery with no renders (5), no launcher installed at the
  project root (6), a launcher that has drifted from the pin (7). Each of those
  states previously produced a CLEAN review, which is the whole defect: this
  pass is not a gate, so an empty or misdirected batch is indistinguishable from
  a surface that was looked at and found sound. `tools/ui-review-preflight-canary.sh`
  is its canary — one arm per refusal reason, each asserting its own exit code.
  It prints its own arm count on a green run, which is the number to read; a
  tally here would rot on the next arm (`claude-md-policy.md`).
- **Reuse the artifacts that exist; add no pipeline.** The inputs are the
  committed gallery images under **`tools/devcards/docs/widgets/<UNIT>/`** (one
  per family — see `gallery.clj` `family-renders`) and the per-card `dump_tree`
  the runner already captures (`core.clj` `render-one!`). Both halves of that
  path were wrong and both were silent:
  - **REPO-ROOT-ANCHORED, unlike every other path in this file**, which is
    devcards-tool-relative (`goldens/`, `corpus/spec.edn`, `gallery.clj`). This
    bullet is read by an agent working from the REPO root, and `docs/widgets/`
    from there is the Obsidian proto vault — which has no `widgets/`, so the
    unanchored form resolved to nothing even here.
  - **`<UNIT>`, not `<WIDGET>`** — `SKILL.md` is the definition and it means a
    WidgetType enum directory OR a composition unit slug. Reading it as
    widgets-only silently drops `legos` and `kitchen-sinks` — exactly the
    composition cards the review is most often about. Their share of the
    committed renders is not written here because it moves with the corpus;
    `preflight.sh` prints the per-unit roster, which is the answer.

  At a CONSUMER that path is the consumer's OWN gallery and never the pin's;
  resolve it with `.claude/skills/ui-standard-review/preflight.sh`, which
  refuses a gallery inside the pin instead of reviewing it. Anything the review
  WRITES goes through
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

## `dev/` probes — the RUN is the result, never the banner

The files under `tools/devcards/dev/` are measurement code, and each opens with
a prose banner. **A banner that states a RESULT is a second source of truth for
a number the file itself computes**, and only one of the two is ever
re-obtained: the probe gets re-run, the banner does not. They diverge in
silence, and the reader who quotes the banner never learns the probe disagrees
with it — so diff a banner's numbers against a fresh run before quoting either.

A banner therefore says what the probe MEASURES, what it reads, and how to run
it, never what it found. That is `.claude/rules/claude-md-policy.md`'s law about
a literal beside a live source — "a second silently divergent source" — reaching
the probe tier, which that rule cannot reach on its own: it is scoped to `*.md`
and so does not load where these files are edited.

## Secret-free — gate-enforced
- Generic widgets, compositions, and generic meta-node examples only.
  Proprietary device meta-nodes (DDE, camera controls) NEVER land here; private
  consumers run THIS runner via their protogen pin against their own private
  corpora.
- `corpus-secret-findings` (`gates.clj`) scans every CARD population — the
  atomic widget cards, the kitchen sinks, and the composition inventory — and
  within a card it scans every string, prose included, since the corpus file IS
  the public artifact. It rides the normal verdict, so a hit exits non-zero.
  Widening its match classes is a deliberate change that owes its own
  false-positive measurement.
- **BUT ITS POPULATION IS CARDS, AND THE CORPUS FILES CARRY PROSE OUTSIDE ANY
  CARD.** This bullet used to read "scans EVERY card population — prose
  included", which a reader takes as "every string in the corpus file" — it is
  not. The gate receives card maps only (`gates.clj`'s three call sites:
  `run-gates`, and `core.clj` for the kitchen sinks and the composition
  inventory), so the widget-level `:notes` / `:deviation` prose that sits BESIDE
  `:cards`, and the spec's own top-level prose, reach no scanned population at
  all. The unscanned population is NOT small and NOT short — it holds
  widget-level prose beside `:cards` plus the spec's own top-level prose, and
  its longest single string runs over a thousand characters. The counts are
  deliberately not written here: they move with every corpus edit, and a stale
  one would understate exactly the exposure this paragraph exists to name.
  Re-derive them from `spec.edn` when you need them. A device landmark pasted
  into a widget's `:notes` ships and nothing here reds.
  Left as a NAMED GAP rather than closed, deliberately: extending the
  populations means passing widget maps with `:cards` stripped (otherwise every
  card is scanned twice and every hit is reported twice), which is a change to
  what the gate judges and owes its own false-positive measurement, exactly as
  the match classes do. Close it as its own change; do not read this bullet as
  cover for prose the gate cannot see.
