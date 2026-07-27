# UI Quality Contracts

The interface-quality standard protogen defines and its consumers gate on.

**SCOPE: a ui_ast SURFACE, never a repository.** Everything here binds an
interface rendered through the ui_ast vocabulary by the reference interpreter,
including widgets a consumer authors on top. The mechanical test is whether the
nodes come out of `controls_dump_tree`. A UI in any other technology is outside
these gates — not by courtesy but because the machinery cannot see it — while
still owing the panel-and-operator readability numbers and the hardware bench
obligations, which never depended on a widget toolkit. `CLAUDE.md` §"Consuming
the UI standard" carries the full statement.

**Companion to `INTERFACE-CONTRACTS.md`, and SEPARABLE from it.** That document
is what a consumer owes for consuming the WIRE; this one is what a surface owes
for RENDERING ui_ast. Nothing here is a wire surface, and **a consumer can owe
that document entirely and this one's GATES not at all** — a client that speaks
the protocol and draws its own interface is exactly that case. What it still
owes is the scope paragraph above, which is a statement of obligation and not a
gate.

The rules here are enforced by finding-producers in
`tools/devcards/src/devcards/` and are consumed through the registry
(`devcards.findings`), never re-implemented in a consumer.

## 0. Two verdict SHAPES, and which measurement earns which

| shape | earned by | verdict |
|---|---|---|
| **exact** | integer arithmetic on inclusive rects — geometry: occlusion, overlap, the layer contract. **And equally** token-level contrast against a declared floor. **This row is the SHAPE a measurement earns, never an inventory of what ships** | pass / fail |
| **banded** | a measurement whose separating gap is narrower than its own seed-to-seed noise. **No such producer ships here** — see below | pass / fail / **uncertain** |

Neither shape may borrow the other's. Geometry has no noise floor, so an
"uncertain" verdict there would manufacture doubt the arithmetic does not have.
A noise-banded measurement is the only legitimate place for an adjudicator, and
one must be validated as a classifier on a held-out labelled set before it is
wired in.

**NO READABILITY LANE SHIPS HERE, and the distinction matters for which shape a
future one takes.** Token-level contrast is EXACT arithmetic against a declared
floor — same shape as geometry, no noise band, no adjudicator. **The three-way
shape belongs to a DIFFERENT quantity**: an ink-DRIFT measurement over a degraded
render, whose separating gap is narrower than its own seed-to-seed noise. Do not
carry "readability is three-way" across to the arithmetic one; they are two
quantities that share a word.

**PDL-HW** — legibility under a hardware condition (sunlight, darkness, a
specific panel revision) — is a BENCH obligation scoped to a hardware revision,
never a gate result. No gate here can see those conditions, so no pass message
may imply them, and no green here may be read as legibility under any condition
the gate did not impose.

---

## 1. The layer contract

### 1.1 The base rule

Two elements may not overlap, and may not sit closer than the configured
minimum clear pixels. `devcards.geometry/separation` is the measurement:
negative = sharing pixels, `0` = touching with nothing between, `n` = `n` fully
clear pixel columns or rows.

The base rule alone is too strict for real interfaces, because deliberate
stacking is how interfaces are built. Layers are how a design says which
stacking is deliberate.

### 1.2 A layer is DECLARED, and z comes from INTENT

A layer declaration carries a `z` value and applies to a subtree. When two
elements are closer than the threshold, each walks up to its **nearest
declaring ancestor**; those two layers are what the contract compares.

**`z` MUST be declared by intent and MUST NOT be derived from observed paint
order.** This is the single most important sentence in this document. A checker
that reads `z` from what currently renders asserts that the system does what it
does — it cannot fail, and it blesses whatever bug is on screen.

The known-bad case is the proof. The video proxy currently covers the chrome. A
checker that inferred "the proxy is on top, therefore the proxy has higher z"
would report the current screen CLEAN and would have ratified the defect. With
chrome declared above video by intent, the same checker correctly FAILS,
because the compositor punches video over chrome in a raster pass after LVGL
has finished.

### 1.3 Declaration is a FIELD, not a marker element

A layer is a field on an existing node, never a wrapper object inserted to mark
one. A marker that exists as an `lv_obj` has geometry, so it participates in the
very geometry checks it was added to describe — the instrument would perturb the
measurement. Default `z` is `0`, so an undeclared subtree gets
the strict base rule rather than an exemption.

### 1.4 The outcome matrix

| relationship | geometry | verdict |
|---|---|---|
| same layer | overlap | **violation** |
| same layer | touch, within threshold | **violation** |
| different layer, higher-z painted on top | overlap | OK — the declared stack |
| different layer, **lower-z painted on top** | overlap | **violation** — z-inversion |
| different layer, equal z | overlap | **violation** — ambiguous stack |
| **both are host-proxy surfaces** | overlap | **violation** — unjudgeable stack (§1.6) |
| different layer | merely touching | OK |
| one is an ancestor of the other | any | not judged — containment is composition |
| either is hidden, or under a hidden ancestor | any | not judged — draws nothing |
| either has no `:coords` | any | **`:unmeasurable-node`** — reported, never skipped |

Only the SAME-LAYER rows are proximity rules. Every other row reasons about
which element is painted *over* the other, and boxes that share no pixel are not
painted over each other at all — so raising the threshold must never make a
different-layer pair fire.

The matrix is exhaustive over the inputs this contract can see. It is silent
about anything it cannot: `layer_top` / `layer_sys`, transforms and opacity, and
partially-transparent elements — an element painted "over" another may still
leave it fully legible, and this contract does not model that.

### 1.5 The three inputs

The checker needs all three together, and a producer signature shaped
`(fn [tree] …)` cannot express it:

1. **The tree** — `dump_tree`, parsed. Supplies geometry and paint order.
2. **The declaration** — intent-declared `z` per subtree. Comes from the
   consumer, never from the render.
3. **The proxy rects** — compositor rects for host-proxy surfaces. **Not
   derivable from the tree**: the punch happens after LVGL finishes, so actual
   stacking is not readable from widget child order.

Plus **ancestry**, which `devcards.invariants/annotate-tree` precomputes as a
`:path` on every node; `related?` is the prefix test. Without it the contract
reports every button against its own label.

### 1.5b A non-static host_proxy is the interaction target

**Put only DECORATION inside a host_proxy that is draggable, resizable or
alignable. An interactive control there is dead.**

This is BY DESIGN. The proxy's glass overlay is full-bleed and carries
`LV_OBJ_FLAG_PRESS_LOCK`, so it takes every press inside the proxy's box and a
control underneath never receives one. A proxy in an interactive mode exists to
be dragged, resized or aligned; routing some of its presses to a child would
make the drag surface unpredictable exactly where the child sits.

Measured, tapping node centres taken from `dump_tree`, with an identical button
outside the proxy as a control:

| mode | button INSIDE the proxy | control OUTSIDE |
|---|---|---|
| `static` | fires | fires |
| `draggable` | **nothing** | fires |
| `resizable` | **nothing** | fires |
| `alignable` | **nothing** | fires |

In `static` mode the proxy clears its own `CLICKABLE` so events fall through —
that is the mode where content interactivity works.

**Why this needs stating rather than leaving to discovery.** The failure is
silent in both of a consumer's oracles: the framebuffer is identical whether the
child was reachable or dead, and the symptom IS that no event fires, so an event
log shows nothing either. A consumer that puts a button inside a draggable proxy
has a dead button and no gate that can tell them. `devcards.interaction`'s
`proxy-content-inert` canary pins the behaviour by INJECTING A POINTER, in both
directions — a one-sided test would pass against a renderer that had stopped
delivering events entirely.

**The interpreter DECLARES this composition, and the overlap lane reads the
declaration.** `dump_tree` carries `proxy_root` on the proxy box and
`proxy_part` (`glass` / `handle` / `cell`) plus `proxy_owner` on each affordance
the renderer builds. `devcards.overlap` excludes a pair only when BOTH nodes sit
in the SAME proxy and at least one is an affordance — the stack this section
describes. It has to come from the renderer because those objects never pass
through `finalize_widget`, so they carry no uid and nothing else can name them,
here or in any consumer; and inferring it from paint order is what §1.2 forbids.

The exclusion is deliberately narrow, and each boundary has a canary:
two DIFFERENT proxies' surfaces still fire (their order lives in the compositor,
§1.6); an affordance against a node outside its proxy still fires; and two
ordinary content children of one proxy still fire, because nothing about the
proxy makes THEIR overlap intentional.

### 1.6 Determining OBSERVED stacking

- **Within the widget tree:** LVGL paints a parent, then its children in index
  order, so the element appearing LATER in a depth-first pre-order traversal
  paints later and is on top. `lv_indev_search_obj` walks children in REVERSE
  and returns the first hit, so the pointer agrees with paint: the later sibling
  wins both.
  **Pre-order is not vector `compare`.** Clojure's vector comparator is
  COUNT-first — `(compare [0 0] [1])` is positive though `[0 0]` paints first —
  so an implementation must not use it as a stand-in. Enumerating pairs in
  pre-order and taking the second element is the whole of the rule.
- **Host-proxy surfaces:** the SURFACE is painted by the compositor after LVGL,
  therefore on top of everything it intersects, regardless of tree position.
  This is why proxy rects are a distinct input. A proxy's own widget CHILDREN
  are not lifted with it — the punch lands on the proxy's rect, so anything LVGL
  drew inside it ends up *under* the video.
- **Two proxy surfaces overlapping each other** cannot be ordered from these
  inputs at all: both are punched after LVGL and their relative order lives in
  the compositor. That is a violation, not a fallback to tree order.
- **Out of scope:** `layer_top` / `layer_sys`. `dump_tree` dumps
  `lv_screen_active` only, so the contract says nothing about them.

### 1.7 Failure modes this contract must be built against

Both were observed in a POC and are the shapes an implementation reproduces
first.

**Red for the wrong reason.** A first run went red on same-layer overlap between
a button and its own label — legitimate nesting — while the z-inversion clause
never fired at all. Stopping at "it goes red" would have reported a validated
design on evidence that said nothing about z-inversion. Every clause owes a
canary that fails for *that clause*, proved by mutation: break the clause, watch
its canary and only its canary fail.

**Silent nil.** Proxy-uid resolution used a keep-nil lookup, so a proxy matching
no node became `nil`, was dropped, and counted as ordinary chrome. The entire
z-inversion check reported CLEAN, and its broken output was byte-identical to
its nothing-to-report output. An unresolved proxy id MUST throw, naming the id.

---

## 2. The overlap rule

`devcards.overlap`. The narrower, ordering-free ancestor of the layer contract:
no two independently-placed elements that can take the pointer may share a
pixel.

### 2.1 The hazard is input, not appearance

When two things that can take the pointer occupy the same pixel, exactly one
gets the press and the other is dead there. No pixel oracle can see it — the
framebuffer is identical whether the occluded control was reachable or not.

### 2.2 `DISABLED` does not exempt anything

`LV_STATE_DISABLED` does **not** remove a widget from the pointer path:

| fact | source |
|---|---|
| hit test gates on `LV_OBJ_FLAG_CLICKABLE` alone | `lv_obj_pos.c:1201` |
| the pointer search skips only `HIDDEN` | `lv_indev.c:623` |
| `DISABLED` gates only whether the press EVENT is sent, after the object is already claimed as `indev_obj_act` | `lv_indev.c:1339`, `:1391`, `:1403` |
| this renderer's `enabled_when` toggles state and never clears `CLICKABLE` | `renderer/src/renderer.c:1722-1750` |

A disabled control painted over an enabled one therefore **absorbs the press and
drops it** — the silent version of the defect, because the control underneath is
dead *and* nothing anywhere reports a press.

**CONSUMER AUDIT OWED — arming a lane NARROWS this one, it does not discharge
it.** Neither PIXEL oracle nor EVENT log can see it: the framebuffer is
byte-identical whether the control underneath was reachable or dead, so no pixel
test fires, and no event is emitted, so no event log fires either.

What CAN see it is `devcards.overlap`, which declines to exclude a disabled node
and names that participant in its `:detail`. Read that report for exactly what it
is: overlap is ORDER-FREE, so it tells you the two share a pixel and one of them
is disabled — never that the disabled one WINS the hit test. That is the
necessary condition, not the verdict, which is why arming the lane leaves an
audit still owed.

So the rest has to be looked for directly. Audit your own screens for **stacked
interactive elements where one is disabled**, starting from the pairs the lane
reports. Any design reasoning that
"disabled controls are safe to stack" — a disabled overlay left mounted over a
live control, a disabled full-bleed scrim, a control disabled *because* another
is meant to receive the press — is the shape to hunt. If you find none, that is
worth recording; if you find one, the fix is the LAYER CONTRACT (declare the
stacking intent), not an exemption.

### 2.3 What is excluded

- **Related nodes** (one an ancestor of the other) — containment is composition.
- **`HIDDEN` nodes and their subtree** — `lv_indev_search_obj` returns `NULL`
  immediately, so they can neither take the pointer nor deny it.

Snapped-away carousel pages are **not** exempted, and they do not need to be at
ANY threshold: a snapped page sits outside its content box, so the descent-gate
clip (§2.4) records it `:unreachable` and it never enters the pairing at all.
That is a positive determination, not a waiver.

What IS threshold-dependent is how much LAYOUT the lane reports. Re-measured
over all 244 cards on this tree — and identical under the shipped
classification table and under the class-census probe table with every class
forced interactive, so no classification choice is doing the work:

| `:overlap/gap-px` | findings | of which on tabview-NAMED cards |
|---|---|---|
| `0` (strict overlap — a shared pixel) | 0 | 0 |
| `1` (touching also fires) | 80 | **66** (56 on `lv_tabview` cards + 10 on the tabview kitchen sink) |

At the strict-overlap default nothing fires and no exemption is owed. At
`gap-px 1` every one of the 80 is an ABUTMENT at 0px, and the participant list
has to include the ACTIVE PAGE or the arithmetic does not close: a tabview's
content container and its active page carry the SAME box, so every pair against
the content is reported twice. That is the tab bar against both, one tab button
against the next, and each tab button against both — 66 across the six
tabview-named cards — plus the scrubber legos' vertically stacked rows for the
other 14. (Bar-against-content alone would give 6 on a 3-tab card; the measured
figure is 10.)

**No pair is two SNAPPED pages**, and none can be: a snapped-away page sits
outside its content box, so the descent-gate clip records it `:unreachable` and
`overlap` drops it BEFORE the pair comprehension — a determination, not an
exemption, and independent of the threshold. Exactly one page per card is
reachable, so a page-vs-page pair is impossible. Do not read that as "no page
participates": the active page is a participant in 26 of the 66. So a consumer
raising the threshold floods the lane with its own layout — the rule working,
not a defect in it — and the zero above must not be read as unconditional.

An earlier revision of this table read 17 / 97 and attributed the 66 to
carousel pages touching. Both numbers predate the work that took the
strict-overlap lane to zero (the interpreter declaring its own proxy
composition, and clearing `CLICKABLE` on two decorative widgets); the gap-px-1
drop of exactly 17 is consistent with those being the same 17 findings, though
this note does not re-derive that. **Re-measure rather than trusting any count
here** — see `devcards.overlap/producer`'s closing paragraph, which makes the
same point about a census it had already lost.

### 2.4 The box that is judged, and what it cannot see

The rule judges the REACHABLE box: a node's click area
(`lv_obj_get_click_area` — coords grown by `ext_click_pad`, which is what
`lv_obj_hit_test` tests), intersected with every ancestor's descent gate.
Both halves are needed, and each alone is wrong in the opposite direction.
Judging `:coords` under-reports wherever a widget extends its touch target;
judging the raw click area over-reports, because `lv_indev_search_obj`
descends into a node's children only while the point stays inside each
ancestor's `coords` — click-area pixels outside an ancestor are dead to the
pointer, and naming them describes a hazard region no pointer can visit.

Some facts remain unseeable. None occurs in this corpus, which is why the rule
assumes them away rather than guessing; each is stated because a consumer's
screens are not bound by that. Read the list as the rule's known blind spots,
not as a closed set — the test for adding one is whether `lv_indev_search_obj`
consults something the dump omits.

- A **transform**. `lv_indev_search_obj` inverse-transforms the point before
  both the descent gate and the hit test, while the dump's `:coords` are
  untransformed. Under a non-identity scale or rotation the reachable box is
  therefore wrong in BOTH directions. This is reachable from the `ui_ast`
  vocabulary — `PROP_SCALE_X/Y`, `PROP_ROTATION`, `PROP_PIVOT_X/Y` — so a
  consumer that transforms an interactive subtree gets answers this rule
  cannot justify, and should treat the lane as unmeasured there.
- `LV_OBJ_FLAG_OVERFLOW_VISIBLE` widens the descent gate by the node's ext
  draw size. Nothing sets it — not the renderer, not LVGL — so on THIS tree
  the gate is exactly `:coords`. A consumer that sets it would see this rule
  clip too hard and UNDER-report.
- `LV_OBJ_FLAG_ADV_HITTEST` lets a widget refuse a hit inside its own box by
  answering `LV_EVENT_HIT_TEST`. Only `lv_image` sets it, and `lv_image` clears
  CLICKABLE at construction, so no node reaches the pairing with it set. A
  consumer that re-adds CLICKABLE to an image would see an OVER-report, because
  the answer lives in an event handler no dump can serialise.

---

## 3. Classification

`devcards.classify` — two axes, deliberately separate. `devcards.lvgl-classes`
ships the starter table for the classes this renderer emits.

- **`:interactive?` is MECHANISM.** Does LVGL put this class in the pointer
  path? That is `LV_OBJ_FLAG_CLICKABLE`, set by `lv_obj_constructor` on every
  object (`lv_obj.c:584`). **Scoped to the classes this renderer emits**, five
  clear it: `lv_image`, `lv_label`, `lv_line`, `lv_spinner`, and
  `lv_roller_label` via its `lv_label` base. That scope is load-bearing rather
  than pedantic — `lv_arclabel` also clears the flag and `lv_menu` adds and
  removes it per instance across a dozen sites; neither is emitted here today,
  so a widget added to the corpus owes this derivation again.
- **`:role` is INTENT.** `:text` and `:interactive` are what a human reads or
  aims at — any occlusion is damage. `:structural` is chrome whose partial
  covering is ordinary composition.

Encoding intent into `:interactive?` would silence the case where a read-only
indicator is stacked on top of a control and kills it. An `lv_bar` is a display
widget by intent and clickable by mechanism, and it is `:interactive? true`
here.

**An undeclared type is a FINDING, never a skip.** A rule that passes over what
it could not classify reports "clean" and "I could not look" as the same empty
vector.

---

## 4. Per-role thresholds

A single global occlusion threshold cannot work. The visible ratios below
interleave across the good/bad boundary rather than separating.

**Provenance:** these four rows were measured in a CONSUMER's screen corpus, not
in this repo — the uids are that consumer's, no protogen corpus or run produces
them. They are recorded as the evidence the design rests on, and they are
exactly as strong as an unreproducible measurement can be.

**protogen's corpus cannot corroborate them, and this has now been measured
rather than assumed.** Over all 244 cards, 169 nodes report a `vis_px` below
their own area — and every one of them is a DELIBERATE mechanism, not damage:

- 155 are fully occluded (ratio 0.0000), and they are hidden proxy affordances
  (61), closed dropdown popup lists (30), and carousel pages the tabview has
  snapped out of view (30), plus kitchen-sink and lego instances of the same.
- The 14 partials are designed or probes: eleven are `lv_roller_label`, whose
  vertical overflow IS the wheel illusion (§2.3's designed-geometry exclusion),
  and three are probe cards exercising a known limit.

So this corpus holds no occluded-and-DAMAGED element at all, which is the exact
population §4's claim is about. Its per-role ratios do not interleave
(`:structural` tops out at 0.332 while `:text` starts at 0.682) — and that is
NOT evidence against the consumer's table. It is evidence that the corpus has
no damaged cases to interleave, so a clean separation here means nothing about
whether one exists on real screens.

Do not re-run this expecting a different answer, and do not promote the
separation above into a threshold: a number fitted to a corpus containing no
defects would pass everything. protogen ships no occlusion lane at all — not
per-role and not global (§0: `:zero-visible-area` is armed but measures
ancestor CLIP, a different quantity); when one lands, the table it uses still
owes a measurement on a corpus that contains damage, which means a consumer's.

| uid | kind | VISIBLE fraction | verdict |
|---|---|---|---|
| 951 | label | 0.3333 | broken |
| 900 | container | 0.8677 | benign |
| 962 | label | 0.9180 | latent damage |
| 950 | wrapper | 0.9413 | benign |

Any threshold catching 962 also fires on 900; any threshold sparing 900 also
misses 962. **The split cannot be a number — it must be what the element is.**
That conclusion is what the rows support, and it is the whole of what they
support.

### What is NOT settled

The role arms below are stated in two different quantities, and only the first
is derivable from the rows above:

| role | arm | status |
|---|---|---|
| `:text`, `:interactive` | visible fraction must be 1.0 — any occlusion is a finding | consistent with the rows: both labels fire, which is the intent |
| `:structural` | covered ≤ 0.02 | **inconsistent with the rows as written** |

Applied literally to the same quantity, a covered bound of 0.02 fires on uid 900
(covered 0.1323) and uid 950 (covered 0.0587) — both recorded BENIGN. So either
the structural arm is measured against something other than the whole-element
visible fraction, or the bound is wrong. The source measurement does not say
which, and guessing would bake a number nobody can defend into a gate that
condemns real screens.

**Therefore:** the `:text` / `:interactive` arm is ready to implement; the
`:structural` arm is NOT, and protogen ships no per-role occlusion lane until it
is re-measured here against a corpus this repo can reproduce. Shipping the table
as though it were coherent is the kind of green-looking lie §0 exists to forbid.

Thresholds are DATA. Each producer declares its own with a default and a
predicate; the registry namespaces them by producer id, **throws on an unknown
key**, and **throws when two producers collide on one key**, so neither a typo
nor a naming clash can quietly relax the gate it names.

---

## 5. Evolving this document

The rules here are implemented by named namespaces, and those namespaces are the
authority for their own mechanics — this document states the CONTRACT and the
reasoning, not a copy of the code. When a rule changes:

1. Change it at the source in this repo. A quality rule living only in a
   consumer is a defect live in every other consumer that has not yet tripped
   over it.
2. Keep the canary that fails for that clause, and re-prove it by mutation.
3. Say in the commit message what each consumer must do — the CONSEQUENCES beat
   is the instruction every pin-bump author executes verbatim.
