---
id: ui.WidgetNode
proto: ui/ui_ast.proto
package: ui
type: message
---

# WidgetNode

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | type | [[proto/ui.WidgetType]] | defined enum value only |
| 2 | x | int32 | - |
| 3 | y | int32 | - |
| 4 | text | string | max-len: 255 |
| 5 | bindings | map<string, string> | - |
| 6 | event | [[proto/ui.EventBinding]] | - |
| 7 | layout | [[proto/ui.Layout]] | - |
| 8 | children | repeated [[proto/ui.WidgetNode]] | - |
| 9 | style_groups | repeated [[proto/ui.StyleGroup]] | - |
| 10 | obj_props | [[proto/ui.ObjProps]] | - |
| 11 | button_props | [[proto/ui.ButtonProps]] | - |
| 12 | label_props | [[proto/ui.LabelProps]] | - |
| 13 | slider_props | [[proto/ui.SliderProps]] | - |
| 14 | image_props | [[proto/ui.ImageProps]] | - |
| 15 | arc_props | [[proto/ui.ArcProps]] | - |
| 16 | bar_props | [[proto/ui.BarProps]] | - |
| 17 | switch_props | [[proto/ui.SwitchProps]] | - |
| 18 | checkbox_props | [[proto/ui.CheckboxProps]] | - |
| 19 | dropdown_props | [[proto/ui.DropdownProps]] | - |
| 20 | roller_props | [[proto/ui.RollerProps]] | - |
| 21 | textarea_props | [[proto/ui.TextareaProps]] | - |
| 22 | spinbox_props | [[proto/ui.SpinboxProps]] | - |
| 23 | spinner_props | [[proto/ui.SpinnerProps]] | - |
| 24 | led_props | [[proto/ui.LedProps]] | - |
| 25 | line_props | [[proto/ui.LineProps]] | - |
| 26 | scale_props | [[proto/ui.ScaleProps]] | - |
| 27 | buttonmatrix_props | [[proto/ui.ButtonMatrixProps]] | - |
| 28 | table_props | [[proto/ui.TableProps]] | - |
| 38 | tabview_props | [[proto/ui.TabviewProps]] | - |
| 40 | chart_props | [[proto/ui.ChartProps]] | - |
| 41 | host_proxy_props | [[proto/ui.HostProxyProps]] | - |
| 48 | target_overlay_props | [[proto/ui.TargetOverlayProps]] | - |
| 29 | visibility | [[proto/ui.VisibilityBinding]] | - |
| 30 | bind_formats | map<string, string> | - |
| 31 | obj_flags | uint32 | - |
| 32 | obj_flags_clear | uint32 | - |
| 33 | states | uint32 | - |
| 34 | scroll_dir | uint32 | - |
| 35 | grid_col_dsc | repeated int32 | - |
| 36 | grid_row_dsc | repeated int32 | - |
| 37 | bare | bool | - |
| 39 | in_tab_bar | bool | - |
| 42 | checked_when | [[proto/ui.VisibilityBinding]] | - |
| 45 | enabled_when | [[proto/ui.VisibilityBinding]] | - |
| 50 | pending_when | [[proto/ui.VisibilityBinding]] | - |
| 46 | color_when | [[proto/ui.ColorBinding]] | - |
| 47 | hit_slop | uint32 | <= 64 |
| 49 | designed_overlay | bool | - |
| 43 | uid | uint32 | - |
| 44 | gestures | repeated [[proto/ui.GestureSpec]] | max-items: 9 |


## Oneofs


### widget_props

Fields: #10, #11, #12, #13, #14, #15, #16, #17, #18, #19, #20, #21, #22, #23, #24, #25, #26, #27, #28, #38, #40, #41, #48


### _x

Fields: #2


### _y

Fields: #3


### _scroll_dir

Fields: #34





## Field Notes


### type (#1)

Which widget this node builds. Closed to defined `WidgetType` members: an unrecognised type names no LVGL constructor, so it is refused rather than drawn as a default box.


### text (#4)

Static text for the widgets that carry one — label, checkbox, textarea, button. Bounded at 255 characters.


### hit_slop (#47)

Touch affordance: grows this node's HIT box beyond its drawn box by this many design pixels on all four sides (`lv_obj_set_ext_click_area`, which is one value per object — LVGL has no per-side form, so neither does this). DPI-scaled through `LV_DPX`, and 0, the default, means the hit box IS the drawn box. The slop is invisible to layout, so its author owes the reserved space to every interactive sibling.


### gestures (#44)

Pre-encoded gesture-to-command templates, meaningful only on a gesture-surface host-proxy node. Bounded at nine, which is the maximum legal registry rather than a guess: one entry per defined `GestureKind`, plus one extra for each kind whose decisions carry a signed step.



