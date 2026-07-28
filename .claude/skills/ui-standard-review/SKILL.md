---
name: ui-standard-review
description: Batched visual review of committed devcard gallery renders plus their dump_tree DOM against this repo's UI standard. One agent loads the standard once and judges MANY elements, emitting findings in the registry's {:card :invariant :node :detail} shape. Owed before any push that modifies what a ui_ast surface renders — here and wherever such a surface is authored.
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

Before any push that modifies what a ui_ast SURFACE renders — the renderer C
source, the theme, the `ui_ast` vocabulary, the corpus, or the committed
gallery — here AND wherever such a surface is authored (`CLAUDE.md`
§"Consuming the UI standard"). Owed for the surface, not for the repository. Nothing
mechanical enforces this, the same way nothing mechanical enforces the
antagonistic review in §"Fixing protogen from a consumer": running it is the
obligation, and its findings must be dispositioned before the push, not after.

The normal entry point is the **`ui-standard-review` agent**
(`.claude/agents/ui-standard-review.md`), which pins the model tier and reads
this file as its first act, so the review runs in its own context and the batch
below is not competing with a session's other work. Invoking this skill
directly is equivalent and is the right shape when you are already the only
thing in the context. Either way it is ONE reviewer over a large batch.

**It reviews a ui_ast SURFACE, not a repository.** Its only inputs are the
committed gallery renders and the `dump_tree` behind them, so a repo that also
ships a DOM or native front-end owes this pass for its ui_ast overlay and
nothing for the rest — there is no gallery and no dump for the rest to review.

**Scope is decided by what PAINTS the surface, not by whether you can run the
dump.** A surface whose pixels come from `controls.wasm` interpreting a
`ui.Screen` is in scope, harness or no harness; `controls_dump_tree` is how you
INSPECT an in-scope surface and never how you decide scope
(`CLAUDE.md` §"WHAT THIS BINDS"). So a consumer who has not stood up a runner
yet is **owed a gallery**, not exempt from the review: that state is an
obligation NOT YET DISCHARGED, and `preflight.sh` below exits non-zero saying
exactly that rather than letting an empty batch print clean.

## AT A CONSUMER — install the launcher, anchor the paths, run the preflight

Consumers run this skill through their protogen pin against their OWN gallery.
Their fixtures never land here — the corpus secret-scan (`gates.clj`) is what
holds that line. Three things have to be true before a single image is judged,
and **each of them used to fail silently, producing a clean report over a
surface nobody looked at.**

### 1. The launcher does not exist at a consumer until you install it

**Agents and skills are discovered from the PROJECT ROOT's `.claude/`, never
from a submodule mount.** With the pin at `proto/protogen/`,
`subagent_type: ui-standard-review` does not exist and this skill is not in the
skill listing — so `CLAUDE.md`'s "RUN IT by launching the `ui-standard-review`
AGENT" has no first step. Measured, Claude Code 2.1.220, three identical mounts
differing only in what sat at the consumer's `.claude/`: with nothing there,
neither name appears in either listing; with a copy, both appear; **with a
relative symlink into the pin, both appear.**

Install it once, and re-check it at every pin bump:

```sh
# from YOUR repo root, with the pin at proto/protogen
mkdir -p .claude/agents .claude/skills
ln -s ../../proto/protogen/.claude/agents/ui-standard-review.md \
      .claude/agents/ui-standard-review.md
ln -s ../../proto/protogen/.claude/skills/ui-standard-review \
      .claude/skills/ui-standard-review
```

**Prefer the symlink over a copy, and the reason is not tidiness.** Through the
link, this file and `STANDARD.md` stay the PIN's bytes and cannot drift from
them — which is what §"WHY IT MAY NOT BE FORKED" asks for, made mechanical
instead of disciplinary. A copy also registers and is the fallback where
symlinks are unusable (a checkout with `core.symlinks=false`), but it must then
be re-synced at every pin bump and `preflight.sh` fails loud once it has
drifted. Either way: **do not edit either file at your end.**

### 2. The two roots, and never resolving one against the other

Every path in this skill resolves against ONE of two roots. Confusing them is
the whole defect class here, and it fails green in both directions.

| what | root | at protogen | at a consumer |
|---|---|---|---|
| the CODE you run — the runner, `core/render-one!`, `dev/class_census.clj`, this file, `STANDARD.md`, `preflight.sh`, every `.claude/rules/…` path | the **PIN** | the repo root | the submodule mount, e.g. `proto/protogen/` |
| the RENDERS you review — the gallery | the **SURFACE under review** | the repo root | **YOUR** repo root, never the mount |

Resolving the gallery against the pin is the worse of the two failures: the
literal path `tools/devcards/docs/widgets/` from a consumer root resolves to
nothing (empty batch, clean report), but a `**` glob for it resolves **under the
mount**, where protogen's own 732 committed renders live — and reviewing those
produces a plausible-looking report that discharges nothing. Both were measured
in a simulated mount; the second returned 732 protogen images and zero of the
consumer's own.

`.claude/rules/…` in particular is anchored at PROTOGEN's root and is
path-scoped to `tools/devcards/**` there, so it does not auto-load at a
consumer: read it explicitly from the pin.

### 3. Run the preflight, and take the batch from it

```sh
# from the SURFACE's own repo root
proto/protogen/.claude/skills/ui-standard-review/preflight.sh
# or, if your gallery is not at tools/devcards/docs/widgets:
UI_REVIEW_GALLERY=ui/gallery/widgets \
  proto/protogen/.claude/skills/ui-standard-review/preflight.sh
```

It prints the unit roster and render counts, and **that roster IS the batch —
do not glob for more.** Otherwise it refuses, with one exit code per reason so
a red is attributable to the clause that produced it:

| exit | state | why it is not "clean" |
|---|---|---|
| 3 | no gallery at the resolved path | obligation NOT YET DISCHARGED, not out of scope |
| 4 | the gallery resolved inside the PIN | you were about to review protogen's surface |
| 5 | gallery present, no unit holds a render | an empty batch reports clean |
| 6 | no launcher at the project root | the agent cannot be launched here at all |
| 7 | launcher installed but drifted from the pin | a local edit is a silent fork of the standard |
| 8 | cannot tell which checkout is the pin | a copied skill would compare itself with itself |

`tools/ui-review-preflight-canary.sh` in the pin is its canary: 11 arms, each
asserting its own exit code, so breaking one clause reds that clause's arms and
no others.

**A clean preflight is NOT a discharged review.** It says the reviewer would
look at the right images. It says nothing about whether anyone looked.

## Load the standard ONCE

Read `.claude/skills/ui-standard-review/STANDARD.md` at the start of the
session, before the first element.

It is GENERATED from this standard's own sources by
`tools/devcards/src/devcards/standard_brief.clj` — which is the honest
statement of what those sources are, since the page is a function of that
namespace and everything it reads. The point is that the briefing is one read
instead of a re-derivation per element. Do not hand-edit it, and do not re-read
the canonical sources element by element; if STANDARD.md disagrees with them,
regenerate it and fix the SOURCE, never patch the prose copy.

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

**Anchored per the two-root table above.** `<SURFACE>` is the repo root of the
surface under review — protogen's own root here, YOUR root at a consumer.
`<PIN>` is protogen's root — the same root here, the submodule mount at a
consumer. Never substitute one for the other, and take `<GALLERY>` from
`preflight.sh` rather than assembling it by hand.

**The renders.** Committed, generated, DO-NOT-EDIT. At protogen `<GALLERY>` is
`<SURFACE>/tools/devcards/docs/widgets`; a consumer's may be anywhere in its own
tree, which is why the preflight prints the resolved path:

```
<GALLERY>/<UNIT>/<UNIT>-<state-slug>-<family>.jpg
<GALLERY>/<UNIT>/README.md
```

`<UNIT>` is the WidgetType enum directory or a composition unit's slug — BOTH,
and the distinction is load-bearing: protogen's own gallery is 24 units, of
which `legos` and `kitchen-sinks` are compositions carrying 48 of the 732
renders. A batch that reads `<UNIT>` as widgets-only drops exactly the cards
this pass is most often about.
`<state-slug>` is the card id's tail past the class segment with slashes turned
to underscores (`gallery/cell-label` → `gallery/state-slug`), so card
`lv_slider/pressed/medium/max` is `pressed_medium_max`. `<family>` is a
`:file-suffix` from `gallery/family-renders`, which is a closed set — a fourth
render set is a deliberate doc-contract change, not something this pass invents.

**The families are the same card rendered differently.** Judging them together
is what catches a theme-specific defect: a token that only collapses in one
family looks like a correct render until its siblings are next to it.

**The DOM.** `dump_tree`, produced in-process by `core/render-one!` with
`:dump? true` (`<PIN>/tools/devcards/src/devcards/core.clj`) and consumed by the
invariant lanes and the gallery cropper. Nothing commits it, so a batch obtains
it from a run of its own. `<PIN>/tools/devcards/dev/class_census.clj` is the
shape to copy: a tracked, read-only probe that renders each card and parses its
dump — the CODE is the pin's, the CORPUS it is pointed at is the surface's.
(`<SURFACE>/tools/devcards/out/findings.edn` is NOT a source of trees — it holds
the deterministic lanes' FINDINGS, and on a clean corpus it is an empty vector.)
Runs happen in the pinned toolchain container
(`<PIN>/.claude/rules/uber-container.md`).

**No dump available is a REPORTED state, never a quiet narrowing.** If the
surface has no harness yet, say so in the report as an obligation outstanding
and name the DOM-dependent invariants you therefore could not judge — the same
rule as the per-card case below, applied to the whole surface.

**Never infer a DOM value from pixels.** If a card's tree is not available for
this pass, say so in the report and do not judge the DOM-dependent invariants
for it. "I could not look" and "clean" must not print the same.

## Open the file — and open it again before you dismiss a finding

The honesty requirements below make `:detail` *checkable*. **This section
requires that the check was actually PERFORMED**, which is not the same thing,
and the gap between the two is where the one measured failure sits.

Before a finding leaves this pass, open what it names. The file must exist at
the path given, and the claim must be visible in it. Where the claim has a
cheaper instrument than an eye, use that instrument: "these two renders are
identical" is `sha256sum`, not a judgement.

**This is not the non-reproducibility argument and is not covered by it.**
`CLAUDE.md` refuses this pass a gate verdict because a VLM is not
reproducible (§0's own reason is different — it forbids borrowing a verdict
SHAPE across measurements, and never mentions VLMs) — variance around an answer, addressed by never wiring it into the
verdict. A claim about a file that is not there is a different failure: fluent,
numerically specific, and indistinguishable from a real finding by the time it
reaches disposition, so nothing downstream catches it.

**A DISMISSAL IS A CLAIM ABOUT THE TREE AND CARRIES THE SAME BURDEN**, and that
is the direction that actually failed here. Measured once, on the
`WIDGET_ROLLER` unit at `4988febb`: four findings, every one re-checked by hand,
and one recorded as FABRICATED — *"there is no such render"*. All three grounds
for that verdict are false against this tree:

- the render exists in all three families —
  `tools/devcards/docs/widgets/WIDGET_ROLLER/WIDGET_ROLLER-hovered_medium_mid-{vanilla,asgard-dark,asgard-light}.jpg`,
  and `git ls-tree 4988febb` lists all three at the reviewed commit;
- `lv_roller/hovered/medium/mid` is a card in `tools/devcards/corpus/spec.edn`
  and `:hovered` is in that unit's `:committed-states`;
- the finding's numeric claim is TRUE. The vanilla hovered and vanilla default
  renders are one sha256 (`863f1c13…`); their `asgard-dark` siblings are two
  different hashes.

**The mechanism is the durable part: absence of OUTPUT is not absence of a
FILE.** The check ran `ls | grep -i hovered`, got nothing, and read nothing as
proof. Here `ls` resolves to an alias that prints nothing at all when its stdout
is not a terminal — `ls | wc -l` returns 0 in the directory where
`/usr/bin/ls -1 | wc -l` returns 58 — so it emits exactly the same empty result
for *no such file* and for *listed nothing*. Establish existence with something
whose empty answer you can tell apart from a broken one: `Read` or `Glob` on the
full path, `git ls-tree` at the commit under review, or a lister you have
confirmed listed something else.

That dismissal was expensive rather than merely wrong. Invariants run over
family 0 only; families 1/2 get per-card hash equality *to each other*, and
`core.clj` says their DOM is "explicitly UNJUDGED, never implied clean". The
distinctness gate likewise judges "one family's hashes (the asgard family is the
styled one the contract judges)" (`gates.clj`), where those two roller cards DO
differ. So a state that collapses only under vanilla is green everywhere — the
discarded finding was in a class no deterministic oracle here judges, which is
the only class this pass exists to reach.

**The mandate is unchanged.** Still mandatory to run, still mandatory to
disposition; this adds a step before a finding is acted on and removes nothing.
And do not carry a fabrication base rate away from this repo — there is none to
carry. The one review where every finding was re-checked yielded no confirmed
instance, because its single fabrication verdict does not itself survive
re-checking. Carry the check, not a number.

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
  rule judges the REACHABLE box — each node's click area (coords grown by
  `ext_click_pad`, which is what `lv_obj_hit_test` tests), intersected with
  every ancestor's descent gate. UI-QUALITY-CONTRACTS §2.4 records what that
  still cannot see (a transform, `OVERFLOW_VISIBLE`, `ADV_HITTEST`); none of it
  is yours to patch by eye.
- **Layer inversion, ambiguous z, unjudgeable proxy stacks** — `devcards.layers`
  and the §1 outcome matrix. `z` is DECLARED intent; a reviewer reading stacking
  off what renders would bless exactly the defect §1.2 exists to catch.
- **Occlusion arithmetic** — never state a covered or visible fraction. §4
  records that the role arms are not settled and §0 that protogen ships no
  occlusion lane at all; supplying an eyeball number in that gap bakes in a threshold
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
  arithmetic has no noise floor; a noise-banded measurement is three-way, and
  §0 requires an adjudicator in its uncertain band to be validated as a
  classifier on a held-out labelled set before it is wired in. This pass is
  neither — and note §0 also records that NO readability producer ships here,
  so there is no lane result for you to agree or disagree with. Report
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
  silence. `:vlm/illegible-contrast` is uncertain by default — no readability
  producer ships here to settle it (§0), so your eye is the only instrument
  that looked and it is not one that can conclude.
- **Hardware conditions are out of scope.** Sunlight, darkness, a panel
  revision — PDL-HW is a bench obligation scoped to a hardware revision (§0).
  This pass sees the gate's own fixed veil and nothing more.

## Disposition

Every finding is FIXED, EXEMPTED, or — for an UNCERTAIN finding only — DEFERRED
TO THE INSTRUMENT that can settle it. Nothing is left un-dispositioned.

DISCARDED-as-not-real is a fourth outcome, and one of TWO with no proof
machinery standing behind it — no `:rationale`, no `:retires-when`, no
`:owner`, no `:expires`, no stale
check to catch it later. `devcards.invariants`'s `validate-exemptions!` and its
`:stale-exemption` finding cover EXEMPTED alone; a DEFERRAL is explicitly not an
exemption-list entry either (see below), so it is unvalidated for the same
reason. What makes DISCARDED the sharper of the two is that a deferral still
names an instrument that will settle it, while a discard closes the question. It therefore carries its evidence inline, per
§"Open the file", and a discard whose evidence is a command that printed nothing
has no evidence at all.

**The uncertain case is not a loophole; it is forced.** `:vlm/illegible-contrast`
is uncertain BY DEFAULT — §0 records that no readability producer ships here, so
nothing deterministic can confirm or refute you — and so "fix it" would round
uncertainty up into a defect and "exempt it" would round it down into a
settled judgement — and §0 forbids both. Exempting is the worse of the two: an
exemption asserts *we looked and it is acceptable*, which is exactly the claim
an eye cannot make where the instrument cannot.

A DEFERRAL carries the same proof burden as an exemption — what would settle
it, and where that is tracked — and it is only available to a finding reported
UNCERTAIN. A deferral that never acquires its instrument is a finding about the
standard, not a disposition.

Note what a deferral is NOT: it is not an entry in the exemption list. The VLM
lane is deliberately not wired into the verdict, so a `:vlm/` exemption would
match no finding in any run that executes — a stale entry in a lane nothing
reads. Record the deferral where the instrument's own work is tracked.

**Fixed** at the source, in this repo, never worked around at a consumer's call
site (`CLAUDE.md` §"Fixing protogen from a consumer"). A fix that shifts pixels
re-mints goldens AND the gallery together, in the same change
(`.claude/rules/devcards.md`) — then re-run this pass over the re-minted images,
because the images you judged no longer exist.

**Exempted** with the same proof every other exemption owes — no weaker bar for
being a vision finding:

```clojure
{:card "…" :invariant :vlm/…
 :rationale "…" :retires-when "…"
 :owner "…" :expires "YYYY-MM-DD"}
```

**Do NOT add `:act/test-mode :manual` unless the precondition below holds.** The
mode is a PRODUCER declaration, never a finding's: `findings.clj` THROWS if a
producer fn emits `:act/test-mode`, and the registry stamps it from the
producer's own `:test-mode`, only when that differs from `:automatic`. Findings
you emit BY HAND in the `{:card :invariant :node :detail}` shape above pass
through no producer, so their mode is `:automatic`. The matcher compares the
mode on both sides, so an entry naming `:manual` will not match them:

| exemption entry | result against a hand-emitted VLM finding |
|---|---|
| without `:act/test-mode` | `{:live 0, :exempted 1, :stale 0}` — matches |
| with `:act/test-mode :manual` | `{:live 1, :exempted 0, :stale 1}` — un-exempts it AND goes stale |

`:act/test-mode :manual` is required ONLY IF the VLM lane is armed as a registry
producer declaring `:test-mode :manual` — which `.claude/rules/devcards.md`
forbids in THIS repo ("Do not wire it into the verdict"), and which no `:vlm/`
producer anywhere in `src/` does. To tell which case you are in: if a producer
in your armed vector has a `:vlm/`-namespaced `:id` and declares `:test-mode
:manual`, add the key; otherwise omit it. The narrowing axis still earns its
keep where it applies — without it an entry can swallow a DETERMINISTIC finding
sharing the card, invariant and node.

`invariants/validate-exemptions!` names the accepted key set in one place —
`invariants/exemption-keys`, which is derived from the finding side rather than
re-spelled, since re-spelling is how the mode came to be missing from it.
`:rationale`, `:retires-when`, `:owner` and `:expires` must each be a
non-blank string, and an entry is a WAIVER rather than a disabled rule:
`:owner` names who to ask and `:expires` is an ISO-8601 `YYYY-MM-DD` date at
most 90 days out. AN EXPIRED WAIVER IS A HARD FAILURE, and so is one dated
beyond that horizon — a date nothing can reach is prose wearing a date. The
two clauses are separable so neither can mask the other, and `:retires-when`
still carries what no machine can evaluate: the EVENT that makes the entry
unnecessary, where the date only says when the decision must be re-taken
regardless. An exemption matching no
finding is itself a finding (`:stale-exemption`), so the list can only shrink —
which is why a `:retires-when` naming a condition that can actually be observed
is the whole value of the entry.
