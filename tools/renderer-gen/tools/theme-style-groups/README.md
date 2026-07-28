# Native child-theme style-group emitter

This tool emits the explicit style groups attached by
`renderer/src/theme.c`. It compiles and executes that source with recorders at
the `lv_style_set_*` and `lv_obj_add_style` boundaries. It does not parse C and
does not maintain a second style table.

From `tools/renderer-gen`:

```sh
bash tools/theme-style-groups/generate.sh \
  --output generated/theme-style-groups.json

bash tools/theme-style-groups/generate.sh \
  --check generated/theme-style-groups.json
```

The default projection resolves at the renderer battery's DPI 160. Pass
`--dpi N` to emit another resolved DPI. Every output enumerates light/dark,
Asgard/vanilla, and representative small/medium/large display-size tiers.
Stock is recorded as a no-op child family because `theme_apply` returns before
attaching a group.

The manifest is deliberately scoped. It contains:

- each named child-theme group and its group-local resolved properties;
- every target class/context, part, state selector, and attachment order;
- the universal pre-dispatch scrollbar attachment as target `"*"`;
- hashes of the source, token projection, renderer configuration, LVGL pin,
  and emitter used.

It does not contain the stock parent theme's styles, per-node AST
`style_groups`, inheritance/cascade resolution, or composited draw colors.
Those are separate inputs to any static readability analysis. Treating this
manifest as a complete effective-style table would be incorrect.

Freshness is part of the renderer-gen unit suite. The generator also:

- links LVGL's real color and palette implementations;
- fails to link if `theme.c` adds an unclassified `lv_style_set_*` call;
- fails if a reset/attached style is absent from the named-group roster;
- checks the three LVGL object accessors modeled by the probe before execution;
- replays the whole attachment capture at all twelve mode/display-size points
  and refuses to emit if the set is not point-independent.

That last guard is what licenses emitting `applications` without a mode or size
axis. `theme_apply` branches on `t->family` alone today, so the topology is
constant across modes and sizes — but the manifest does not assume that, it
re-derives it on every run. A `theme.c` that grew a dark-only or size-only
attachment fails generation rather than emitting a manifest correct at one point
out of twelve.
