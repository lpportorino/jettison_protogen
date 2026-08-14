# Authoring screens — the EDN vocabulary, top to bottom

This is the reference for writing a screen `.edn` under `edn/screens/` (and for
any consumer corpus compiled by this pipeline). It documents the GRAMMAR — the
shapes and the rules that make them mean what they mean. The rosters those
shapes draw from (token names, widget tags, style props) each have one home in
code, named where they come up; a copied list here would drift, so none is
copied.

The pipeline a screen rides: read tokens + EDN → validate (Malli,
`src/lvgl_codegen/schema.clj`) → resolve components
(`src/lvgl_codegen/component.clj`) → semantic validate → expand class strings
and `:style` maps (`src/lvgl_codegen/expand.clj`) → emit protobuf ui_ast
(`src/lvgl_codegen/emit_proto.clj`). Every stage fails loud with the offending
value named; nothing is defaulted past an error.

## The screen envelope

```clojure
{:events   {…}   ; event name → event definition (may be {})
 :subjects {…}   ; subject name → {:type :int|:string, :default optional}
 :tree     {…}   ; ONE root widget node
 :type     :screen}
```

All four keys are required. `:subjects` declares every name the screen binds or
mutates; a binding to an undeclared subject is a validation error, not a
runtime surprise. The schema for the whole file is `screen-schema` in
`src/lvgl_codegen/schema.clj`.

## A widget node

A node is a closed map — an unknown key is a validation error. The two keys
that define it:

- `:tag` — an `lv_*` keyword from `valid-widget-tags`
  (`src/lvgl_codegen/schema.clj`), or a NON-`lv_` keyword, which is a component
  reference (see Components).
- `:children` — a vector of nodes. Order is meaning: it is z-order, flex order,
  and (for `:lv_tabview`) the per-index zip with `:tab_names`.

The rest of the node surface, grouped by what it does:

- **Styling**: `:class` (the class DSL, below), `:style` (the map form, below).
- **Content**: `:text`.
- **Placement**: `:x`/`:y` (absolute px), `:cell` (grid placement sugar),
  `:grid-cols`/`:grid-rows` (grid track lists — px int, `:content`, or
  `[:fr n]`).
- **Behavior**: `:event` (names a declared event), `:bind` (subject bindings:
  `:text`/`:value`/`:checked`, plus `:mode` on `:lv_host_proxy` only),
  `:bind-fmt` (printf-style format per bound key), `:checked-when` /
  `:enabled-when` / `:color-when` / `:show-when` / `:pending-when` (reactive
  comparisons against an `:int` subject; `:pending-when` drives the
  outstanding-answer state bit and conflicts with a static
  `:states #{:user-1}`, which validation rejects).
- **LVGL surface**: `:flags`/`:flags-clear`/`:states` (keyword sets derived
  from LVGL's own headers — the enums in `src/lvgl_codegen/generated/enums.clj`
  are the roster), `:scroll-dir`, `:bare` (strip the theme's `lv_obj`
  background/border: grouping without chrome), `:in-tab-bar`,
  `:designed-overlay`.
- **Identity**: `:id` — sibling-scoped identity for tree patching. REQUIRED on
  stateful widgets (`stateful-widget-tags`): without it, positional identity
  breaks on sibling reorder and silently loses user runtime state.
- **Widget props**: the `*_props` passthrough maps — see the wire boundary
  below.

## The class DSL

`:class` is one string of whitespace-separated tokens (or a vector of strings,
joined at the pipeline boundary). Four token shapes:

1. **Layout directives** — `flex`, `flex-row`, `flex-col`. These select the
   flex flow; they are not style props. `flex` alone means the LVGL default
   flow (row). Wrapping/reversing flows have no class spelling — author
   `:layout` directly.
2. **Exact shorthands** — `w-content`, `h-content` (LV_SIZE_CONTENT),
   `items-center`, `justify-center`.
3. **Registry-prefix tokens** — `<prefix><tail>`, resolved longest-prefix-first
   against the style-props registry (`src/lvgl_codegen/style_props.clj`, the
   one home for the prop roster and their prefixes). The tail is one of:
   - a **token ref**: `bg-surface-1`, `text-fg-0`, `font-font-body`,
     `p-spacing-sm`, `rounded-radius-card`, `opa-disabled-opa` — the tail names
     a design token, and its KIND must match the prop's resolve kind (a color
     prop cannot reference a spacing token);
   - a **percent**: `w-pct-100`;
   - an **int literal**: `w-36`, `h-12`.
4. **Class macros** — `@name`, expanded from `:class-defs` in
   `edn/components.edn`. One level only: a macro must not reference another
   macro. That is deliberate — a macro is a NAME for a token run, not a
   composition system, and one level is what keeps "what does this expand to"
   answerable by one lookup.

Any token may carry prefix modifiers, colon-separated, at most one from each
axis:

- **Breakpoint** — `sm:` `md:` `lg:` `xl:` (min-width tiers; the HIGHEST
  matching tier wins regardless of token order — EXCEPT between `sm:` and a
  bare token, which share a tier; see the `sm:` warning below).
- **State** — `pressed:` `focused:` `disabled:` `pending:` — derived from
  `state-selector` in `src/lvgl_codegen/style_props.clj`, which is the roster;
  a term added there joins the prefix parse by itself.

`md:p-spacing-sm`, `pressed:bg-pressed-surface`, `md:pressed:bg-accent-bg` are
all valid. A typo'd prefix throws — it would otherwise silently apply always.
A PREFIXED LAYOUT DIRECTIVE throws too: `ui.Layout` carries no breakpoint or
state variants, so `md:flex-col` cannot mean anything.

### `sm:` SCOPES NOTHING — it shadows the base token at every canvas

`bp-min-index` in `src/lvgl_codegen/style_props.clj` gives `:sm` minimum
composite index 0, which is the SAME minimum a token with no breakpoint
prefix gets, and `expand->variants` applies a token at index `idx` exactly
when `idx >= min-idx`. So an `sm:` token applies at all eight composite
indices — the identical set a bare token applies at — and `sm:X` is
indistinguishable from `X` at every canvas. There is no smallest-tier band
for it to scope: `sm` IS the floor. Measured through the real expander:

```text
"w-80 sm:w-64"  ->  64 at all eight indices   base never applies anywhere
"sm:w-64 w-80"  ->  80 at all eight indices   ORDER decided it, not tier
"sm:w-64"       ->  64 at all eight indices   identical to a bare w-64
"w-80 md:w-64"  ->  80 80 then 64 from idx 2  a real tier scope, for contrast
```

Two consequences, and the second is the one that ships defects:

- The tier bullet above does NOT hold between `sm:` and a bare token. They
  are one tier, so the LAST of the two in the class string wins, CSS-style —
  order decides, not height.
- The prefix's only reachable effect is to SHADOW the base token beside it
  while reading as responsive. `X sm:Y` renders `Y` everywhere: the base
  value never applies at any canvas, and an author reviewing the string
  believes `Y` is a small-canvas concession it is not.

**The correct idiom is the inverse: author the small-canvas value BARE and
widen upward with `md:` / `lg:` / `xl:`.** That is what the macros in
`edn/components.edn` already do: `@btn-primary` carries a bare `p-spacing-xs`
and an `md:p-spacing-sm` beside it — small at the floor, grown from the
second tier up.

**Do NOT "restore the authored value" by deleting an `sm:` override.** Every
`sm:` token authored in this repository SHRINKS what it shadows, so dropping
the override picks the LARGE value at every canvas — the one value the author
demonstrably did not want. Nor is the naive transposition a repair: `X sm:Y`
rewritten as `Y md:X` INVERTS the pair rather than restoring it, since `X` was
never the wide-canvas intent, only the shadowed base. The repair is to author
base-small and widen upward, with the large-canvas value chosen deliberately.

This is not hypothetical. A row authored `gap-spacing-sm sm:gap-spacing-xs`
renders the `xs` gap at EVERY canvas rather than only the smallest, and a
focus ring calibrated against the wider `sm` base gap overran it at every
rendered cell. The `:class-defs` preamble in `edn/components.edn` carries that
measurement and the LVGL arithmetic behind it.

Design tokens live in `edn/tokens.edn` (layer 1 primitives + layer 2
semantics); the pipeline consumes the RESOLVED projection
(`output/manifests/design-tokens.json`), optionally overlaid by a
consumer-private token file. The token names in a class string are the resolved
manifest's keys.

## The `:style` map

The same resolution stream in map form, for props with no class prefix (and
for widget PARTS, which have no class spelling at all):

```clojure
:style {:pad-all 0
        :line-color :fg-0          ; keyword = token ref, same resolution
        :text-opa 127              ; literal = wire value
        :md {:pad-all 8}           ; breakpoint scope (nests a prop map)
        :pressed {:bg-opa 200}     ; state scope
        :indicator {:radius :radius-circle}}  ; widget-part scope (leaf)
```

Breakpoint and state scopes may combine once, in either order. Part scopes are
leaves. `:style` entries desugar AFTER the class tokens, so a `:style` prop
overrides a same-tier class token. `:cell` desugars into the same stream as
grid-cell props; authoring the same grid-cell prop in both `:cell` and
`:style` is an error.

## The wire boundary — `*_props` maps are proto spellings

Node-level keys are kebab-case authoring vocabulary. The per-widget
`slider_props` / `arc_props` / `led_props` / … maps are a deliberate
PASSTHROUGH to the proto IR: their keys and values are the WIRE spellings —
snake_case field names from `proto/ui/ui_ast.proto`, enum fields as keywords
the emitter translates. That is why `{:max_value 100}` sits beside `:class` in
one map. The deep shapes are enforced at emit by the proto-IR backstop
(`proto-ser/validate-ir!`), so a typo'd field fails the build naming the wire
path — the authoring schema deliberately leaves these maps open rather than
duplicating the proto surface.

## Components — named templates with typed params

`:components` in `edn/components.edn`. A component is a `:tree` template plus
typed `:params`; a usage is a node whose `:tag` is the component name:

```clojure
{:props {:class "@btn-primary" :event :apply :label "Apply"} :tag :btn}
```

Substitution forms inside a template:

- `$param` inside a **string** (`:text`, `:class`) — interpolated; a `$`
  followed by digits is a currency literal, not a reference.
- `:$param` as a **value** (`:event`, `:bind` values, `*_props` values, nested
  usage `:props` values) — replaced whole.
- `{:slot :default}` — replaced by the usage's `:children`.

Params declare `:type` (`:string` `:int` `:map` `:keyword`), `:required`, and
`:default`. Unknown `:props` keys, missing required params, and type
mismatches all throw naming the component and the param. A residue scan
rejects any `$` reference that survives expansion.

An OPTIONAL param can feed `:class` or `:event`: a `:class` that is entirely
param-fed and substitutes to blank is dropped (meaning "no class"), and an
`:event` whose `:$param` resolves to nothing is dropped (an event-less node).
Only param-fed values are dropped — a template's static empty `:class` stays,
and fails loudly at the token parser.

Deliberate constraints (demand-gated; the docstring of
`src/lvgl_codegen/component.clj` carries them): one flat params map (no
per-child params), no conditional subtrees, every slot receives the same
children. A component is a NAMED SPELLING of a tree, not a programming
language. Resolution is compile-time only — the renderer always receives flat
LVGL widgets.

A component usage that expands to a stateful widget must carry `:id` at the
usage site (the expansion inherits it as its root identity).

## Events and subjects

An event definition's surface (see `event-def` in
`src/lvgl_codegen/schema.clj`): `:trigger` (`:value-changed` / `:long-press`;
default clicked), `:int-value`, `:include-value`, `:set`+`:to` (mutate a local
subject — `:to` without `:set` is rejected: the renderer would drop the value
and reclassify the press), `:toggle`, `:notify-host`, and the pre-encoded
device-command arms (`:cmd`, `:cmd-by-value`). Consumers with a device-command
manifest author higher-level keys that LOWER to those arms before this
pipeline runs; which keys exist is the consumer's contract, not this one.

Subjects are `{:type :int}` or `{:type :string}` with an optional matching
`:default`. The reactive bindings (`:checked-when` / `:enabled-when` /
`:color-when` / `:show-when` / `:pending-when`) and the `:value` / `:checked`
/ `:mode` bind keys compare or read INT subjects only — a string subject there is rejected at
validation because the renderer would read the wrong union member silently.
`:bind {:text …}` accepts both types.

## Pitfalls the validators catch — and the ones they cannot

Caught at codegen (each is a hard error):

- **Content-sizing a childless leaf.** `w-content`/`h-content` on a bar,
  slider, led, arc, switch, spinner, scale, buttonmatrix or chart collapses it
  to ~0px (nothing to measure) — give leaves explicit sizes.
- An undeclared event/subject reference; `:to` without `:set`; both `:states
  #{:checked}` and `:checked-when` on one node (the reactive binding would
  silently win); duplicate sibling `:id`s; a stateful widget without `:id`;
  tabview `tab_names` not zipping 1:1 with content children.

Not catchable mechanically — carry them as authoring knowledge:

- **An arc's indicator sweep must agree with its `:value`**: `end_angle =
  start_angle + value% × (background sweep)`. Both reach the renderer as
  independent fields; contradictory instructions draw two coincident strokes
  rather than a gauge.
- **Font face is what aligns baselines.** LVGL flex has no baseline
  cross-place, so a caption/value pair reads aligned only when both carry the
  SAME face; a size step and a shared baseline are mutually exclusive.
- **Emission order follows authoring order.** Style properties are emitted in
  the order the tokens appear, so reordering tokens in a class string is a
  byte-level change to the `.pb` even when it is visually identical. Keep
  token order stable in refactors that must not move build outputs.

## Where the rosters live

| What | Home |
|---|---|
| Widget tags, node schema, semantic checks | `src/lvgl_codegen/schema.clj` |
| Style props + class prefixes | `src/lvgl_codegen/style_props.clj` |
| Class parsing + variant expansion | `src/lvgl_codegen/expand.clj` |
| LVGL enum keyword sets (`:flags`, `:states`, …) | `src/lvgl_codegen/generated/enums.clj` (factory-generated) |
| Design tokens | `edn/tokens.edn` → `output/manifests/design-tokens.json` |
| Class macros + components | `edn/components.edn` |
| Wire field spellings | `proto/ui/ui_ast.proto` |
