# UI Quality Contracts

The interface-quality standard protogen defines and its consumers gate on.
Companion to `INTERFACE-CONTRACTS.md`, which covers the cross-language WIRE
surface; nothing here is a wire surface. The rules here are enforced by
finding-producers in `tools/devcards/src/devcards/` and are consumed through the
registry (`devcards.findings`), never re-implemented in a consumer — see
`CLAUDE.md` §"Consuming the UI standard".

## 0. The two lanes have different verdict shapes, on purpose

| lane | measurement | verdict |
|---|---|---|
| **Geometry** — occlusion, overlap, the layer contract | exact integer arithmetic on inclusive rects | pass / fail |
| **Readability** — contrast | a measurement whose separating gap is narrower than its own seed-to-seed noise | pass / fail / **uncertain** |

Neither lane may borrow the other's shape. Geometry has no noise floor, so an
"uncertain" verdict there would manufacture doubt the arithmetic does not have.
Readability's uncertain band is the only legitimate place for an adjudicator,
and one must be validated as a classifier on a held-out labelled set before it
is wired in.

**PDL-HW** — legibility under a hardware condition (sunlight, darkness, a
specific panel revision) — is a BENCH obligation scoped to a hardware revision,
never a gate result. No gate here can see those conditions, so no pass message
may imply them. A green readability lane says the render is legible under the
gate's own fixed veil, and nothing more.

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
very overlap and occlusion checks it was added to describe — the instrument
would perturb the measurement. Default `z` is `0`, so an undeclared subtree gets
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

**CONSUMER AUDIT OWED — this one is not discharged by arming a lane.** Neither
of your existing oracles can see it: the framebuffer is byte-identical whether
the control underneath was reachable or dead, so no pixel test fires, and no
event is emitted, so no event log fires either. A screen with this defect passes
everything you currently run.

So it has to be looked for directly. Audit your own screens for **stacked
interactive elements where one is disabled** — most cheaply by arming
`devcards.overlap` against your corpus, which reports exactly these pairs and
names the disabled participant in its `:detail`. Any design reasoning that
"disabled controls are safe to stack" — a disabled overlay left mounted over a
live control, a disabled full-bleed scrim, a control disabled *because* another
is meant to receive the press — is the shape to hunt. If you find none, that is
worth recording; if you find one, the fix is the LAYER CONTRACT (declare the
stacking intent), not an exemption.

### 2.3 What is excluded

- **Related nodes** (one an ancestor of the other) — containment is composition.
- **`HIDDEN` nodes and their subtree** — `lv_indev_search_obj` returns `NULL`
  immediately, so they can neither take the pointer nor deny it.

Snapped-away carousel pages are **not** exempted, and they do not need to be —
but that result is THRESHOLD-DEPENDENT, so read the number with its condition.
Measured over the whole corpus with every class forced interactive:

| `:overlap/gap-px` | findings | of which `lv_tabview` |
|---|---|---|
| `0` (strict overlap — a shared pixel) | 17 | **0** |
| `1` (touching also fires) | 97 | **66** |

At the strict-overlap default no exemption is owed. At `gap-px 1` a carousel's
stacked pages TOUCH by construction and the lane floods. That is the rule
working, not a defect in it — but a consumer raising the threshold must expect
it, and must not read the zero above as unconditional.

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
them, and nothing here reproduces the table. They are recorded as the evidence
the design rests on, and they are exactly as strong as an unreproducible
measurement can be. protogen does not yet ship an occlusion lane; when it does,
the table it uses owes a re-measurement here.

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
`:structural` arm is NOT, and protogen ships no occlusion lane until it is
re-measured here against a corpus this repo can reproduce. Shipping the table as
though it were coherent is the kind of green-looking lie §0 exists to forbid.

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
