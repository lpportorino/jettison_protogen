---
id: ui.BarProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# BarProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | min_value | int32 | - |
| 2 | max_value | int32 | - |
| 3 | value | int32 | - |
| 4 | start_value | int32 | - |
| 5 | mode | [[proto/ui.BarMode]] | defined enum value only |




## Field Notes


### mode (#5)

How the bar fills: normal, symmetrical about the range midpoint, or a two-ended range. Closed to defined `BarMode` members.



