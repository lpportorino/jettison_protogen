# Coverage dual-oracle matrix (Phase 7)

Supersedes the Phase-5 alignment matrix: the same dual-oracle differential
(semantic tree diff + tolerance-0 framebuffer compare), generalized over
(property, value) rows.

- **Proto path**: an EDN fixture per row → `.pb` via the real codegen →
  `controls.wasm`.
- **Reference path**: `reference.wasm` (`src/reference_ui.c`, literal `lv_*`
  calls, zero shared codegen — plan R5) driven by a 2-byte `[prop, value]`
  selector through `controls_load_ui`.

Covered rows (grow per `src/reference_ui.c`'s `ref_prop` cases):

| prop byte |      property      |                                        proto fixture                                         |         oracle emphasis          |
|-----------|--------------------|----------------------------------------------------------------------------------------------|----------------------------------|
| 0         | `lv_align_t`       | box `:style {:align <kw>}`                                                                   | tree (geometry)                  |
| 1         | `lv_text_align_t`  | label child `:style {:text-align N}`                                                         | framebuffer (geometry-invariant) |
| 2         | `lv_text_decor_t`  | label child `:style {:text-decor N}` (incl. the bitmask combo)                               | framebuffer                      |
| 3         | `lv_border_side_t` | box `:style {:border-side N}` (sparse bitmask values)                                        | framebuffer                      |
| 4         | `lv_flex_flow_t`   | box `:layout :flex-*` + 3 children — renders THROUGH the generated `flex_flow_lut`           | tree (geometry)                  |
| 5         | `lv_bar_mode_t`    | `:lv_bar` + `:bar_props {:mode …}` over range [-50,50] — the first widget_props-oneof family | framebuffer                      |
| 6         | `lv_arc_mode_t`    | `:lv_arc` + `:arc_props {:mode …}`                                                           | framebuffer                      |
| 7         | `lv_roller_mode_t` | `:lv_roller` + `:roller_props {:mode …}`                                                     | framebuffer                      |
| 8         | `lv_scale_mode_t`  | `:lv_scale` + `:scale_props {:mode … :label_show true}` (sparse bitmask)                     | framebuffer                      |

Both oracles are always asserted. Run:

```bash
make -f wasm.mk all reference        # in-container: both wasms
bash pocs/06-coverage-matrix/run.sh  # exit 0 = every row matches
```

Adding a row family: add a `ref_prop` case to `src/reference_ui.c` (literal LVGL
calls), a fixture writer + row loop here, rebuild `reference.wasm`. The
extracted enum/setter data (`make factory-coverage`) is the backlog of families
worth adding.

Rows 6–8 (arc/roller/scale modes) follow the bar-mode recipe; each reference
case mirrors `renderer.c`'s exact widget_props setter order. The scale family
documents a contract quirk the matrix itself surfaced: `renderer.c` applies
`label_show` UNCONDITIONALLY (the proto default hides labels where bare LVGL
would show them) — fixtures must set it explicitly.

Rows 9–10: WIDGET SINGLES (every renderer-buildable WidgetType, wire order,
inside the standard box; text on label/checkbox/textarea exactly as the renderer
applies it) and COMBOS (mixed flex row, nested boxes, styled button+label, and
the all-widgets kitchen-sink mega scene). Discovered semantics encoded by these
families: a spinbox (one-line textarea) sets a LOCAL content-driven height that
outranks the renderer's style-group sizing — the proto cannot force a spinbox
height (`size_widget` mirrors this).

Every render of BOTH paths runs under `--assert-content` — the content-sanity
oracle (opaque-pixel share + flat-frame detection): two identically-BLANK
screens would pass the equality oracles, so emptiness is its own failure.
