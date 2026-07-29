# Proven pairs — the (ink, fill) pairs this repo declares together

GENERATED — do not edit by hand. Regenerate with:

```
tools/uber.sh 'cd tools/devcards && clojure -M:proven-pairs'
```

Producer: `tools/devcards/dev/proven_pairs.clj`. Read its docstring for the derivation and for what it cannot see.

Sources of co-declaration, in the order the table folds them: `renderer/src/theme.c`, `tools/renderer-gen/edn/components.edn`, `renderer/edn/screens/*.edn`, `tools/renderer-gen/src/lvgl_codegen/fixtures.clj`.

**This is not a cross product.** A pair appears only where two colour declarations are in force on the same drawn glyph — `docs/UI-QUALITY-CONTRACTS.md` §6.9 states why a declared-FG × declared-BG product is neither sound nor complete, and the producer's docstring re-derives that argument rather than citing it.

Ratios are WCAG 2.x relative-luminance contrast, the same arithmetic `tools/devcards/dev/disabled_pair_probe.clj` measures rendered pairs with, so a declared ratio and a measured one are comparable digit for digit. Verdicts are exact pass/fail on declared values — there is no noise band and no adjudicator. The two floors are WCAG AA body text (4.5:1) and this repo's governing MIL-STD-1472H 5.2.2.7 floor (6.0:1).

**The `as drawn` column is where a ratio stops being the whole story.** `composited (fill-opa)` means every context declaring that pair also fades the FILL (the `opa-` class prefix resolves to `bg-opa`, glyphs untouched), so the rendered pair is a token ink over a blend and the row's ratio is the AUTHORED one. `layer-opa` is the whole-widget fade, which re-composites both ends. Neither composite is computed here — the exact byte depends on the SW blend path, so it has to come from the dump. A row is marked only when EVERY context fades it; one un-faded context means the authored pair really is drawn somewhere and the ratio is exact there.

## Token pairs (34 rows, 17 distinct pairs)

| ink | fill | mode | ink hex | fill hex | ratio | ≥4.5:1 | ≥6.0:1 | as drawn |
|---|---|---|---|---|---:|---|---|---|
| `fg-0` | `status-warning` | dark | #E8E8F0 | #F59E0B | 1.76 | **FAIL** | **FAIL** | — |
| `fg-0` | `accent-bg` | light | #1A1A28 | #5C14D7 | 2.08 | **FAIL** | **FAIL** | — |
| `fg-0` | `status-success` | dark | #E8E8F0 | #10B981 | 2.08 | **FAIL** | **FAIL** | — |
| `fg-0` | `accent-bg` | dark | #E8E8F0 | #B18AF4 | 2.21 | **FAIL** | **FAIL** | — |
| `accent-text` | `pressed-accent` | dark | #1A1A28 | #6B4FA0 | 2.66 | **FAIL** | **FAIL** | — |
| `fg-0` | `status-error` | dark | #E8E8F0 | #EF4444 | 3.09 | **FAIL** | **FAIL** | — |
| `accent-text` | `pressed-accent` | light | #E8E8F0 | #8B5CF6 | 3.47 | **FAIL** | **FAIL** | — |
| `fg-0` | `pressed-accent` | light | #1A1A28 | #8B5CF6 | 4.06 | **FAIL** | **FAIL** | — |
| `fg-0` | `status-error` | light | #1A1A28 | #EF4444 | 4.57 | PASS | **FAIL** | — |
| `fg-2` | `surface-2` | dark | #8686A0 | #1E1E2E | 4.63 | PASS | **FAIL** | composited (fill-opa) |
| `fg-2` | `surface-2` | light | #585846 | #D0D0C0 | 4.64 | PASS | **FAIL** | composited (fill-opa) |
| `fg-2` | `surface-1` | dark | #8686A0 | #12121F | 5.24 | PASS | **FAIL** | — |
| `fg-0` | `pressed-accent` | dark | #E8E8F0 | #6B4FA0 | 5.30 | PASS | **FAIL** | — |
| `fg-2` | `surface-1` | light | #585846 | #E0E0D4 | 5.44 | PASS | **FAIL** | — |
| `disabled-fg` | `surface-2` | dark | #9A9BB6 | #1E1E2E | 6.04 | PASS | PASS | — |
| `surface-2` | `disabled-fg` | dark | #1E1E2E | #9A9BB6 | 6.04 | PASS | PASS | — |
| `accent-text` | `accent-bg` | dark | #1A1A28 | #B18AF4 | 6.39 | PASS | PASS | — |
| `fg-1` | `surface-1` | dark | #9898B0 | #12121F | 6.58 | PASS | PASS | — |
| `fg-0` | `status-success` | light | #1A1A28 | #10B981 | 6.77 | PASS | PASS | — |
| `accent-text` | `accent-bg` | light | #E8E8F0 | #5C14D7 | 6.79 | PASS | PASS | — |
| `disabled-fg` | `surface-2` | light | #3D3C2C | #D0D0C0 | 7.16 | PASS | PASS | — |
| `surface-2` | `disabled-fg` | light | #D0D0C0 | #3D3C2C | 7.16 | PASS | PASS | — |
| `fg-1` | `surface-1` | light | #404050 | #E0E0D4 | 7.63 | PASS | PASS | — |
| `fg-0` | `status-warning` | light | #1A1A28 | #F59E0B | 8.00 | PASS | PASS | — |
| `fg-0` | `pressed-surface` | light | #1A1A28 | #C0C0A8 | 9.28 | PASS | PASS | — |
| `fg-0` | `surface-2` | light | #1A1A28 | #D0D0C0 | 11.02 | PASS | PASS | — |
| `fg-0` | `pressed-surface` | dark | #E8E8F0 | #2A2A3E | 11.49 | PASS | PASS | — |
| `fg-0` | `surface-1` | light | #1A1A28 | #E0E0D4 | 12.91 | PASS | PASS | — |
| `surface-1` | `fg-0` | light | #E0E0D4 | #1A1A28 | 12.91 | PASS | PASS | — |
| `fg-0` | `surface-2` | dark | #E8E8F0 | #1E1E2E | 13.46 | PASS | PASS | — |
| `fg-0` | `surface-0` | light | #1A1A28 | #F0F0E8 | 15.00 | PASS | PASS | — |
| `fg-0` | `surface-1` | dark | #E8E8F0 | #12121F | 15.22 | PASS | PASS | — |
| `surface-1` | `fg-0` | dark | #12121F | #E8E8F0 | 15.22 | PASS | PASS | — |
| `fg-0` | `surface-0` | dark | #E8E8F0 | #0A0A12 | 16.18 | PASS | PASS | — |

### Where each token pair is declared

| ink | fill | declared at |
|---|---|---|
| `accent-text` | `accent-bg` | `kitchen_sink:sm`, `vr-fixtures:sm` |
| `accent-text` | `pressed-accent` | `kitchen_sink:pressed+sm` |
| `disabled-fg` | `surface-2` | `theme-style/disabled_fill:sm` |
| `fg-0` | `accent-bg` | `vr-fixtures:disabled+xl[fill-opa]`, `vr-fixtures:sm`, `vr-fixtures:xl` |
| `fg-0` | `pressed-accent` | `vr-fixtures:pressed+lg`, `vr-fixtures:pressed+md`, `vr-fixtures:pressed+sm`, `vr-fixtures:pressed+xl` |
| `fg-0` | `pressed-surface` | `kitchen_sink:pressed+sm` |
| `fg-0` | `status-error` | `vr-fixtures:disabled+sm[fill-opa]`, `vr-fixtures:pressed+sm`, `vr-fixtures:sm` |
| `fg-0` | `status-success` | `vr-fixtures:disabled+lg[fill-opa]`, `vr-fixtures:lg`, `vr-fixtures:pressed+lg` |
| `fg-0` | `status-warning` | `vr-fixtures:disabled+md[fill-opa]`, `vr-fixtures:md` |
| `fg-0` | `surface-0` | `vr-fixtures:sm` |
| `fg-0` | `surface-1` | `kitchen_sink:sm`, `theme-style/panel:sm`, `vr-fixtures:sm` |
| `fg-0` | `surface-2` | `kitchen_sink:sm`, `theme-style/btnm_items:sm`, `vr-fixtures:lg`, `vr-fixtures:sm` |
| `fg-1` | `surface-1` | `kitchen_sink:sm`, `vocabulary:sm` |
| `fg-2` | `surface-1` | `kitchen_sink:sm` |
| `fg-2` | `surface-2` | `kitchen_sink:sm[fill-opa]` |
| `surface-1` | `fg-0` | `theme-style/roller_sel:sm` |
| `surface-2` | `disabled-fg` | `theme-style/roller_sel_dis:sm` |

## Non-token pairs (0 rows)

One or both ends is a hex LITERAL rather than a declared token — a drawn colour the manifest does not declare, which `docs/UI-QUALITY-CONTRACTS.md` §6.7 calls a FACT rather than a defect. Kept out of the token table because the question that table answers is about the token vocabulary.

| ink | fill | mode | ink hex | fill hex | ratio | ≥4.5:1 | ≥6.0:1 | as drawn |
|---|---|---|---|---|---:|---|---|---|

## The third answer — 149 findings this derivation could NOT classify

An unjudged element is a FINDING, never a skip: a rule that passes over what it could not classify reports "clean" and "I could not look" as the same empty result. Each key below is a distinct reason a pair does not exist or could not be completed. EVERY key is printed with its count, including the ones at zero — a section that vanished when it had nothing to say would print the same thing whether the check ran or not, which is the failure this whole section exists to refuse.

### `class-token-never-visited` — 0

A colour class token present in a source file's TEXT that the structural walk never reached. This is the coverage check: it is EMPTY when the walk saw every colour class the files contain, and non-empty means this table is missing something.

By source: 


### `fill-carries-no-glyph` — 12

A fill no declared ink sits on. `bg-fg-0` in the VR fixtures is the mirror of the case above — a FOREGROUND token used as a FILL, on a box with nothing written on it.

By source: `demo_widgets` 11, `vr-fixtures` 1

- {:source :demo_widgets, :colour "#009688", :where :lv_button}
- {:source :demo_widgets, :colour "#2196F3", :where :lv_button}
- {:source :demo_widgets, :colour "#2196F3", :where :lv_obj}
- {:source :demo_widgets, :colour "#4CAF50", :where :lv_button}
- {:source :demo_widgets, :colour "#4CAF50", :where :lv_obj}
- {:source :demo_widgets, :colour "#607D8B", :where :lv_button}
- {:source :demo_widgets, :colour "#9C27B0", :where :lv_button}
- {:source :demo_widgets, :colour "#F44336", :where :lv_button}
- {:source :demo_widgets, :colour "#F44336", :where :lv_obj}
- {:source :demo_widgets, :colour "#FF9800", :where :lv_button}
- {:source :demo_widgets, :colour "#FFFFFF", :where :lv_obj}
- {:source :vr-fixtures, :colour "#22D3EE", :where :lv_obj}

### `ink-reaches-no-glyph` — 3

An ink declaration no text-bearing node inherits. `hud-label` is the interesting one: it is `text-accent-bg`, a BACKGROUND token authored as INK, and nothing in this repo instantiates it — so the pair a FG/BG cross product would score for it is a pair no source declares.

By source: `demo_widgets` 2, `vocabulary` 1

- {:source :demo_widgets, :colour "#2196F3", :where :lv_label}
- {:source :demo_widgets, :colour "#4CAF50", :where :lv_label}
- {:source :vocabulary, :colour "accent-bg", :where :hud-label}

### `no-declared-fill` — 15

A glyph whose ink IS declared but whose fill is not — it sits on whatever the nearest painting ancestor turns out to be, including the screen itself, which this repo's screens leave transparent.

By source: `demo_widgets` 3, `kitchen_sink` 1, `tabview_demo` 7, `vocabulary` 1, `vr-fixtures` 3

- {:source :demo_widgets, :idx 0, :state nil, :ink "#2196F3", :text ""}
- {:source :demo_widgets, :idx 0, :state nil, :ink "#2196F3", :text ""}
- {:source :demo_widgets, :idx 0, :state nil, :ink "#4CAF50", :text " 17% growth this week"}
- {:source :kitchen_sink, :idx 0, :state nil, :ink "fg-2", :text "Kitchen Sink v1"}
- {:source :tabview_demo, :idx 0, :state nil, :ink "fg-0", :text "About"}
- {:source :tabview_demo, :idx 0, :state nil, :ink "fg-0", :text "All systems operational"}
- {:source :tabview_demo, :idx 0, :state nil, :ink "fg-0", :text "Brightness"}
- {:source :tabview_demo, :idx 0, :state nil, :ink "fg-0", :text "LVGL Controls v1.0"}
- {:source :tabview_demo, :idx 0, :state nil, :ink "fg-0", :text "System Status"}
- {:source :tabview_demo, :idx 0, :state nil, :ink "fg-0", :text "Tabview Demo"}
- {:source :tabview_demo, :idx 0, :state nil, :ink "fg-0", :text "Volume"}
- {:source :vocabulary, :idx 0, :state nil, :ink "fg-2", :text "$label"}
- {:source :vr-fixtures, :idx 0, :state nil, :ink "fg-0", :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :ink "fg-0", :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :ink "fg-0", :text "<computed>"}

### `no-declared-ink` — 102

A glyph whose ink NO source declares. It falls through to whatever ancestor style sets `text_color` at apply time, and which style that is belongs to `theme_apply`'s C dispatch — not to any declaration. Resolving these needs the rendered dump, not this tier.

By source: `demo_widgets` 60, `kitchen_sink` 2, `vocabulary` 4, `vr-fixtures` 36

- {:source :demo_widgets, :idx 0, :state nil, :text "$27,123.25"}
- {:source :demo_widgets, :idx 0, :state nil, :text "$411"}
- {:source :demo_widgets, :idx 0, :state nil, :text "$64"}
- {:source :demo_widgets, :idx 0, :state nil, :text "$722"}
- {:source :demo_widgets, :idx 0, :state nil, :text "$805"}
- {:source :demo_widgets, :idx 0, :state nil, :text "$917"}
- {:source :demo_widgets, :idx 0, :state nil, :text "+79 246 123 4567"}
- {:source :demo_widgets, :idx 0, :state nil, :text "10"}
- {:source :demo_widgets, :idx 0, :state nil, :text "8-15 July, 2021"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Birthday"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Blue T-shirt"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Blue T-shirt"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Blue T-shirt"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Blue T-shirt"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Blue T-shirt"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Clothes"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Clothes"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Clothes"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Clothes"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Clothes"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Costs: -"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Desktop: -"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Elena Smith"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Experience"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Gender"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Hard-working"}
- {:source :demo_widgets, :idx 0, :state nil, :text "High Speed"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Invite"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Item purchased"}
- {:source :demo_widgets, :idx 0, :state nil, :text "LVGL v9.5.0"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Log out"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Low speed"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Mbps"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Milestone reached"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Mobile: -"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Monthly Summary"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Monthly Target"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Monthly revenue"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Network Speed"}
- {:source :demo_widgets, :idx 0, :state nil, :text "New connection"}
- {:source :demo_widgets, :idx 0, :state nil, :text "New message"}
- {:source :demo_widgets, :idx 0, :state nil, :text "New subscriber"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Normal Speed"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Notification"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Out of stock"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Password"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Revenue: 21 %"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Sales: -"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Sessions"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Tablet: -"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Team player"}
- {:source :demo_widgets, :idx 0, :state nil, :text "This is a short description of me. Take a look at my profile!"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Top products"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Unique visitors"}
- {:source :demo_widgets, :idx 0, :state nil, :text "User name"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Widgets demo"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Your profile"}
- {:source :demo_widgets, :idx 0, :state nil, :text "Your skills"}
- {:source :demo_widgets, :idx 0, :state nil, :text "elena@smith.com"}
- {:source :demo_widgets, :idx 0, :state nil, :text "<computed>"}
- {:source :kitchen_sink, :idx 0, :state nil, :text "Edit me"}
- {:source :kitchen_sink, :idx 0, :state nil, :text "<computed>"}
- {:source :vocabulary, :idx 0, :state nil, :text "$label"}
- {:source :vocabulary, :idx 0, :state nil, :text "$price"}
- {:source :vocabulary, :idx 0, :state nil, :text "Blue T-shirt"}
- {:source :vocabulary, :idx 0, :state nil, :text "Clothes"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Click"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Click"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Click"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Flip"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Flip"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Flip"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Go"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Go"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Go"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Hit"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Hit"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Hit"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Measure"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Measure"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Measure"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Ping"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Ping"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Ping"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Under"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Under"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "Under"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "anchor"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "anchor"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "anchor"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}
- {:source :vr-fixtures, :idx 0, :state nil, :text "<computed>"}

### `subtree-not-literal` — 2

A node in Clojure source whose `:children` are built by an expression rather than written as literal maps. Everything under it is unreachable by a non-evaluating read.

By source: `vr-fixtures` 2

- {:source :vr-fixtures, :where :lv_obj, :class "w-80 h-60"}
- {:source :vr-fixtures, :where :lv_obj, :class "w-pct-100 h-pct-100 bg-surface-0"}

### `theme-style-fill-only` — 8

An `lv_style_t` that sets `bg_color` and no `text_color` — `checked_accent` is the one to know: the DROPDOWN selected band takes its glyph colour from the STOCK parent theme, so the pair has no token on the ink side at all and is deliberately not completed from one. The ROLLER no longer belongs in that sentence: its band authors BOTH ends on its own style, which is what removed a constraint the old arm recorded as infeasible — stock sets bg and a white text_color together, so while only the fill was replaced no glyph tone could reach the floor.

By source: `theme-c` 8

- {:source :theme-c, :style "checked_accent", :fill [:checked-accent]}
- {:source :theme-c, :style "disabled_knob", :fill [:disabled-fg]}
- {:source :theme-c, :style "disabled_track", :fill [:surface-2]}
- {:source :theme-c, :style "field_bg", :fill [:surface-1]}
- {:source :theme-c, :style "scrollbar", :fill [:edge-0]}
- {:source :theme-c, :style "tab_bar_bg", :fill [:surface-2]}
- {:source :theme-c, :style "tab_page_bg", :fill [:surface-0]}
- {:source :theme-c, :style "track_bg", :fill [:edge-0]}

### `theme-style-ink-only` — 7

An `lv_style_t` in the theme that sets `text_color` and no `bg_color`. Its fill arrives from whichever other style `theme_apply` puts on the same object, which is a runtime co-application this probe does not model.

By source: `theme-c` 7

- {:source :theme-c, :style "accent_ink", :ink [:fg-0]}
- {:source :theme-c, :style "btn", :ink [:fg-0]}
- {:source :theme-c, :style "cursor_off", :ink [:disabled-fg]}
- {:source :theme-c, :style "disabled", :ink [:disabled-fg]}
- {:source :theme-c, :style "disabled_dim", :ink [:disabled-fg]}
- {:source :theme-c, :style "disabled_flat", :ink [:disabled-fg]}
- {:source :theme-c, :style "tab_txt", :ink [:fg-0]}

