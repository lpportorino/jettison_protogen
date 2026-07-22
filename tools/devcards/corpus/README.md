# Devcard corpus spec (T2.1)

`spec.edn` is the machine-readable card inventory the public fixture builder
(T2.2) consumes: 227 atomic per-widget cards across all 22 widget classes plus 6
kitchen-sink composites. One atomic card = one widget in one state at one size
(optionally one value sample); a gate failure names its exact cell by the stable
id `<tag>/<state>/<size>[/<value-or-variant>]`.

Sources of truth, in priority order:

1. The ratified corpus axes (STATE via wire
   `node.states` bits; VALUE at min/mid/max for slider/arc/bar/spinbox/
   dropdown/roller, states sampled at mid, plus pressed×min/max for the two KNOB
   widgets slider + arc), the UNSTYLED definition, and atomicity.
2. `../conventions/ui-render-conventions.edn` — every widget's
   `:committed-states` list is copied verbatim from `:widget-states` (the
   contract T2.5 gates against); `:state-selectors` likewise. A card whose state
   is not in its class's committed list carries `:expect :inert` (the manifest's
   inertness clause) or a probe marker.
3. The per-widget theme-surface survey (a private authoring-side artifact) —
   its `per_widget[*].devcard` grids supplied each widget's states, sizes,
   sample texts, props, wrappers, and probe cells; its `devcard_matrix`
   supplied `:declared-counts`.

Where the three disagree, the spec follows the ratified axes + conventions
and records a
`:deviation` on the widget (never a silent drop): manifest-committed states the
survey grids omit (`:hovered` on the nine interactive classes, `:focus-key` on
button), the T2.1 value-axis cells, the widget survey's private class-def / token
vocabulary translated to raw renderer-public props, and the lv_led composite
screens atomized. `:declared-counts` (196 + 6 sinks) is the survey's own tally;
`:card-count` (227) is the authored truth.

Notable ratifications baked into the header (read them before building):

- `:render` pins an 800×480 canvas: DISP_LARGE — the tier the mission targets —
  requires `greater_res >= 720`; the F1 POC's 480×320 and the harness's 400×300
  default both silently render DISP_MEDIUM.
- `:builder-laws` extends the F1 laws with root `pad-all 0` (harness geometry,
  not theme styling) so full-bleed cells fit and x/y probes hold.
- `:expect-legend` defines the per-card gate role, including the
  `:probe-pixel-only` class dump_tree is structurally blind to.

Verify after any edit (in the dev container; `clojure.edn/read-string` must
succeed and the counts must stay true):

```
bb -e '(let [s (clojure.edn/read-string (slurp "corpus/spec.edn"))] [(count (:widgets s)) (:card-count s) (= (:card-count s) (count (mapcat :cards (:widgets s))))])'
```

