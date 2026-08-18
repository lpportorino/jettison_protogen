---
id: ui.ScaleProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# ScaleProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | mode | [[proto/ui.ScaleMode]] | defined enum value only |
| 2 | total_tick_count | uint32 | - |
| 3 | major_tick_every | uint32 | - |
| 4 | label_show | bool | - |
| 5 | min_value | int32 | - |
| 6 | max_value | int32 | - |
| 7 | rotation | int32 | - |
| 8 | angle_range | uint32 | <= 360 |
| 9 | text_src | string | max-len: 255 |
| 10 | post_draw | bool | - |
| 11 | sections | repeated [[proto/ui.ScaleSection]] | max-items: 4 |




## Field Notes


### mode (#1)

Scale geometry and label side — the horizontal, vertical and round variants. Closed to defined `ScaleMode` members.


### angle_range (#8)

Angular span of a round scale, in degrees. Bounded at 360 because it is a span of the circle rather than a delta.


### text_src (#9)

Custom major-tick label texts, newline-joined, one per major tick (the `lv_demo_widgets` analytics parity extension). Bounded at 255 characters for the whole set.


### sections (#11)

Coloured value sections (`lv_scale_section_*`), at most four.



