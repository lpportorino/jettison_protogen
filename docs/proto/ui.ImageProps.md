---
id: ui.ImageProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# ImageProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | src | string | max-len: 255 |
| 2 | has_pivot | bool | - |
| 3 | pivot_x | int32 | - |
| 4 | pivot_y | int32 | - |
| 5 | rotation | int32 | - |




## Field Notes


### src (#1)

Image source — an LVGL symbol name or an asset path. Bounded at 255 characters.



