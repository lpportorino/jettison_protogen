---
name: ui-standard-review
description: Batched visual review of committed devcard gallery renders plus their dump_tree DOM against this repo's UI standard. One agent loads the standard once and judges MANY elements, emitting findings in the registry's {:card :invariant :node :detail} shape. Owed before any push that modifies elements, here and in consumer repos.
argument-hint: "[unit-dir]"
allowed-tools: Read, Glob, Grep, Bash
---

# UI standard review — one briefing, many elements

A vision pass over renders the deterministic lanes cannot judge. It looks at the
committed gallery JPEGs and the card's `dump_tree` values together and reports
what a human reviewer would catch and arithmetic cannot: content that reads
wrong, a state that looks like another state, an image that disagrees with its
own DOM.

**This is not a gate.** `make -f renderer.mk check-renderer` is the gate. Every
other lane in this standard is reproducible; this one is not, and
`docs/UI-QUALITY-CONTRACTS.md` §0 forbids a verdict that implies more than its
measurement can see. What this pass produces is *findings owed a disposition*,
never a pass/fail verdict.

## When it is owed, and what launches it

Before any push that modifies elements — the renderer C source, the theme, the
`ui_ast` vocabulary, the corpus, or the committed gallery — in this repo AND in
every consumer that pins it (`CLAUDE.md` §"Consuming the UI standard"). Nothing
mechanical enforces this, the same way nothing mechanical enforces the
antagonistic review in §"Fixing protogen from a consumer": running it is the
obligation, and its findings must be dispositioned before the push, not after.

The normal entry point is the **`ui-standard-review` agent**
(`.claude/agents/ui-standard-review.md`), which pins the model tier and reads
this file as its first act, so the review runs in its own context and the batch
below is not competing with a session's other work. Invoking this skill
directly is equivalent and is the right shape when you are already the only
thing in the context. Either way it is ONE reviewer over a large batch.

Consumers run this skill through their protogen pin against their OWN gallery.
Their fixtures never land here — the corpus secret-scan (`gates.clj`) is what
holds that line.

## Load the standard ONCE

Read `.claude/skills/ui-standard-review/STANDARD.md` at the start of the
session, before the first element.

It is GENERATED from the canonical sources — `docs/UI-QUALITY-CONTRACTS.md`, the
live classification table in `tools/devcards/src/devcards/lvgl_classes.clj`, and
each producer's declared `:thresholds` — so that the briefing is one read
instead of a re-derivation per element. Do not hand-edit it, and do not
re-read the canonical sources element by element; if STANDARD.md disagrees with
them, regenerate it and fix the source, never patch the prose copy.

## The batch protocol — this is the design, not an optimisation

**One agent. One standard load. Many elements.** The briefing is the expensive
part of the context and it is a fixed cost; the marginal cost of the next
element is a few images and a subtree. Spawning one agent per element multiplies
the fixed cost by N and buys no additional signal — and it *destroys* signal,
because the strongest findings in this pass are comparisons ACROSS cards
(state-vs-state, family-vs-family, size-vs-size), which an agent that can see
one element structurally cannot make. Do not fan out. If a batch is too large
for one pass, split it into sequential passes in the SAME agent so the briefing
and everything already seen stay loaded.

### Selecting a batch

The unit of batching is a gallery unit directory — one widget's cards, or one
composition unit — because a unit's cards vary along a declared axis and the
whole point is to see them side by side.

1. Start from the diff. Which units can the change touch? A theme change touches
   every unit; a widget's decode arm touches that widget's unit and any
   composition unit that uses it.
2. Take units whole. Within a unit, take a card's whole family set together.
3. Order the batch so the unit's `default` cards are seen first — every other
   state is judged relative to them.

### Size

Work one unit per pass by default. A pass carries roughly `cards × families`
images, and a working budget of a few dozen images is where each image still
gets real attention; past that, split the unit along the leading axis of the
card-id tail (the state segment) and run the halves as consecutive passes.
**Never split by family** — that discards the cross-family comparison, which is
the cheapest defect class to find here.

### Working a unit

1. Read the unit's `README.md` once — it carries the card roster, the states the
   theme COMMITS to rendering distinct, and the props schema.
2. Judge each card: its family images together, against its DOM values.
3. Then judge the unit as a whole: does the state axis behave monotonically, do
   sizes differ only in size, does one family carry a defect the others do not.
   Findings from this step cite more than one card in `:detail`.

## The inputs

**The renders.** Committed, generated, DO-NOT-EDIT:

```
tools/devcards/docs/widgets/<UNIT>/<UNIT>-<state-slug>-<family>.jpg
tools/devcards/docs/widgets/<UNIT>/README.md
```

`<UNIT>` is the WidgetType enum directory or a composition unit's slug.
`<state-slug>` is the card id's tail past the class segment with slashes turned
to underscores (`gallery/cell-label` → `gallery/state-slug`), so card
`lv_slider/pressed/medium/max` is `pressed_medium_max`. `<family>` is a
`:file-suffix` from `gallery/family-renders`, which is a closed set — a fourth
render set is a deliberate doc-contract change, not something this pass invents.

**The families are the same card rendered differently.** Judging them together
is what catches a theme-specific defect: a token that only collapses in one
family looks like a correct render until its siblings are next to it.

**The DOM.** `dump_tree`, produced in-process by `core/render-one!` with
`:dump? true` (`tools/devcards/src/devcards/core.clj`) and consumed by the
invariant lanes and the gallery cropper. Nothing commits it, so a batch obtains
it from a run of its own. `tools/devcards/dev/class_census.clj` is the shape to
copy: a tracked, read-only probe that renders each card and parses its dump.
(`tools/devcards/out/findings.edn` is NOT a source of trees — it holds the
deterministic lanes' FINDINGS, and on a clean corpus it is the two-byte literal
`[]`.) Runs happen in the pinned toolchain container
(`.claude/rules/uber-container.md`).

**Never infer a DOM value from pixels.** If a card's tree is not available for
this pass, say so in the report and do not judge the DOM-dependent invariants
for it. "I could not look" and "clean" must not print the same.

## What to report

### Shape

Exactly the registry's producer shape, so findings ride the existing verdict and
exemption machinery instead of a parallel path:

```clojure
{:card "lv_slider/pressed/medium/max"   ; the card id, not the file name
 :invariant :vlm/dom-render-mismatch    ; from the closed set below
 :node "lv_slider#12"                   ; type#uid, as the geometry rules label it
 :detail "…what was seen, and where…"}
```

`:card` and `:invariant` are what `findings/check-findings!` requires and what
every exemption matches on. Keep `:card` a real card id — a finding keyed to a
file name can be neither attributed nor exempted.

### The closed invariant set

Namespaced under `:vlm/` so a finding from this pass can never collide with a
deterministic lane's keyword — a collision would let one exemption silence the
other lane.

| `:invariant` | means |
|---|---|
| `:vlm/clipped-content` | glyphs or a graphic cut off by their own box, **where the renderer did not already say so** — see the caveat below |
| `:vlm/dom-render-mismatch` | the image contradicts a value in `dump_tree` (a slider at `min` painted mid-track, a label whose glyphs are not its `text`) |
| `:vlm/state-indistinguishable` | a state the unit README lists as committed-distinct is not distinguishable BY EYE from `default` |
| `:vlm/theme-inconsistency` | a defect present in one family and absent in its siblings, same card |
| `:vlm/cross-card-inconsistency` | sibling cards disagree where their axis says they should agree — a size step that changes more than size, a non-monotonic value axis |
| `:vlm/alignment-defect` | misalignment, uneven spacing, or off-centre content between elements that share no pixel, so no geometry lane sees it |
| `:vlm/visual-artifact` | rendering garbage attributable to the renderer: stray pixels, seams, uncomposited alpha, torn edges |
| `:vlm/illegible-contrast` | foreground not separable from background by eye — always subject to the honesty rule below |

**`:vlm/clipped-content` has a deterministic owner and you are the fallback.**
`invariants/defect-flags` — `:text_truncated`, `:text_clipped`, `:clipped`,
`:overflow`, `:scrollable_overflow`, `:offscreen`, `:squished` — is reported by
the DOM lane on every judged card in this repo, so clipping the RENDERER
noticed is already a machine finding and re-reporting it is the duplication
this section exists to prevent. Check the card's `dump_tree` first. Emit
`:vlm/clipped-content` only for clipping the renderer did NOT flag — that is
the real gap, and it is worth catching precisely because no flag fires there.
Say in `:detail` that you checked and which flags were absent.

The set is closed. A defect that fits none goes under the nearest keyword with
the mismatch stated in `:detail`; inventing a keyword ad hoc breaks exemption
matching. Adding a row is a change to this skill.

## What NOT to report

Duplicating a deterministic lane is noise: it inflates the disposition queue
with findings that already have an owner, and it dresses an unreproducible
observation in a reproducible lane's clothes.

**One caveat you must hold while reading this list.** `devcards.overlap` and
`devcards.layers` are OPT-IN producers. protogen's gate runs through the
finding-producer registry, but those two are not in the producer vector it
passes, so on THIS repo's corpus their classes currently have no machine
owner. Deferring to them is still right, because the answer is to arm the lane
rather than to eyeball geometry that exact arithmetic already decides, and a
model's guess at a rect comparison is worth less than the comparison. But do
not report the class as "covered" — if you notice something in it, say so as an
UNCERTAIN note naming the producer that should own it, so the gap is visible
rather than silently absorbed. In a consumer repo that HAS armed them, the
deferral is literal.

- **Exact overlap** — two pointer-path elements sharing a pixel is
  `devcards.overlap`, measured on inclusive rects. Do not eyeball it. Note the
  rule's own stated conservatism (it measures `:coords`, the pointer is tested
  against the grown click area) — that under-reporting is recorded in
  UI-QUALITY-CONTRACTS §2.4 and is not yours to patch by eye.
- **Layer inversion, ambiguous z, unjudgeable proxy stacks** — `devcards.layers`
  and the §1 outcome matrix. `z` is DECLARED intent; a reviewer reading stacking
  off what renders would bless exactly the defect §1.2 exists to catch.
- **Occlusion arithmetic** — never state a covered or visible fraction. §4
  records that the role arms are not settled and that protogen ships no
  occlusion lane; supplying an eyeball number in that gap bakes in a threshold
  nobody can defend. "This label is unreadable because something covers it" is a
  visual claim and belongs here; "0.33 visible" is not.
- **Golden hashes and pixel identity** — the goldens hash RAW framebuffers; the
  JPEGs are presentation only (`.claude/rules/devcards.md`). They are lossy and
  flattened onto black before encoding, so compression ringing, blocking, and
  the black backdrop under transparent pixels are ARTEFACTS OF THE GALLERY, not
  renderer defects. Never claim two renders are pixel-identical or pixel-
  different from JPEGs.
- **"It looks unstyled"** — corpus cards are unstyled by contract; everything
  unset falls through to the loaded theme, which is the object under test.
- **`DISABLED` reachability** — §2.2 settles it with sources; do not relitigate.

## Honesty requirements

- **A finding here is not a verdict.** Geometry is pass/fail because integer
  arithmetic has no noise floor; readability is three-way, and §0 requires an
  adjudicator in its uncertain band to be validated as a classifier on a
  held-out labelled set before it is wired in. This pass is neither. Report
  findings; do not report a lane result, and never write a pass message that
  implies coverage this pass does not have.
- **Two runs can disagree.** Say so when reporting. A finding that did not
  reappear is not thereby refuted, and one that did is not thereby confirmed.
- **Every finding cites its evidence.** `:detail` names what produced it — which
  file, which region of it, which DOM key and value. A finding whose `:detail`
  cannot be checked by someone opening the same image is not reportable.
- **Uncertain stays uncertain.** Open `:detail` with `UNCERTAIN — ` and state
  what would settle it (a measurement, a second family, a DOM value not
  available this pass). Do not round uncertainty up into a defect or down into
  silence. `:vlm/illegible-contrast` is uncertain by default: the readability
  measurement's separating gap is narrower than its own seed-to-seed noise (§0),
  so an eye cannot resolve what the instrument cannot.
- **Hardware conditions are out of scope.** Sunlight, darkness, a panel
  revision — PDL-HW is a bench obligation scoped to a hardware revision (§0).
  This pass sees the gate's own fixed veil and nothing more.

## Disposition

Every finding is FIXED or EXEMPTED before the push. There is no third state.

**Fixed** at the source, in this repo, never worked around at a consumer's call
site (`CLAUDE.md` §"Fixing protogen from a consumer"). A fix that shifts pixels
re-mints goldens AND the gallery together, in the same change
(`.claude/rules/devcards.md`) — then re-run this pass over the re-minted images,
because the images you judged no longer exist.

**Exempted** with the same proof every other exemption owes — no weaker bar for
being a vision finding:

```clojure
{:card "…" :invariant :vlm/… :rationale "…" :retires-when "…"}
```

`invariants/validate-exemptions!` accepts those four keys and no others, and
requires both strings non-blank. An exemption matching no finding is itself a
finding (`:stale-exemption`), so the list can only shrink — which is why a
`:retires-when` naming a condition that can actually be observed is the whole
value of the entry.
