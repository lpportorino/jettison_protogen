---
id: ui.SliderProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# SliderProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | min_value | int32 | - |
| 2 | max_value | int32 | - |
| 3 | value | int32 | - |
| 4 | mode | [[proto/ui.BarMode]] | defined enum value only |
| 5 | seek_on_press | bool | - |




## Field Notes


### mode (#4)

How the slider fills. It shares `BarMode` with the bar: normal, symmetrical about the range midpoint, or a two-knob range.



