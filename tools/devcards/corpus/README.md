# Devcard corpus spec

`spec.edn` is the machine-readable card inventory the public fixture builder
consumes: per-widget atomic cards across every widget class plus the kitchen-sink
composites. One atomic card = one widget in one state at one size (optionally
one value sample); a gate failure names its exact cell by the stable id
`<tag>/<state>/<size>[/<value-or-variant>]`.

**The only trustworthy tallies are `:card-count` and `:kitchen-sink-count`,
because `devcards.fixtures/load-spec` DERIVES each from the spec's own contents
and throws when they disagree.** Cite those, and nothing else.

This paragraph used to name a third field, `:declared-counts`, as carrying "the
exact tallies". It carried nothing of the kind and has been deleted: no code
read it, and it disagreed with the per-widget `:authored-count` beside it for 15
of 23 widgets while its `:total` sat 50 below the validated `:card-count`. A
hand-typed tally of the tree's own contents rots the day after it is written
— the two surviving counts are safe precisely because they are checked rather
than typed.

`:authored-count` is still present per widget and is still UNVALIDATED, so treat
it as a hint rather than a fact until something derives it.

Sources of truth, in priority order:

1. The ratified corpus axes (STATE via wire
   `node.states` bits; VALUE at min/mid/max for slider/arc/bar/spinbox/
   dropdown/roller, states sampled at mid, plus pressed×min/max for the two KNOB
   widgets slider + arc), the UNSTYLED definition, and atomicity.
2. `../conventions/ui-render-conventions.edn` — every widget's
   `:committed-states` list mirrors `:widget-states` (the states the asgard
   theme renders distinct); the gate checks each card's `:expect`
   (`:distinct` hash≠default / `:inert` hash==default), and a widget records any
   survey-vs-manifest divergence via `:deviation`. `:state-selectors` likewise. A
   card whose state is not in its class's committed list carries `:expect :inert`
   (the manifest's inertness clause) or a probe marker.
3. The per-widget theme-surface survey (a private authoring-side artifact) —
   its `per_widget[*].devcard` grids supplied each widget's states, sizes,
   sample texts, props, wrappers, and probe cells; its `devcard_matrix`
   supplied `:declared-counts`.

Where the three disagree, the spec follows the ratified axes + conventions
and records a
`:deviation` on the widget (never a silent drop): manifest-committed states the
survey grids omit (`:hovered` on the interactive classes, `:focus-key` on
button), the value-axis cells, the widget survey's private class-def / token
vocabulary translated to raw renderer-public props, and the lv_led composite
screens atomized. `:declared-counts` is the survey's own tally; `:card-count` is
the authored truth (spec.edn carries both).

Notable ratifications baked into the header (read them before building):

- `:render` pins an 800×480 canvas: DISP_LARGE — the tier the mission targets —
  requires `greater_res >= 720`; a smaller canvas renders DISP_MEDIUM, or
  DISP_SMALL at `<= 320` (the tier branch lives in `renderer/src/theme.c`).
- `:builder-laws` extends the root full-canvas w/h law with root `pad-all 0` (harness geometry,
  not theme styling) so full-bleed cells fit and x/y probes hold.
- `:expect-legend` defines the per-card gate role, including the
  `:probe-pixel-only` class dump_tree is structurally blind to.

Verify after any edit (in the dev container; `clojure.edn/read-string` must
succeed and the counts must stay true):

```
clojure -M -e '(let [s (clojure.edn/read-string (slurp "corpus/spec.edn"))] (prn [(count (:widgets s)) (:card-count s) (= (:card-count s) (count (mapcat :cards (:widgets s))))]))'
```
