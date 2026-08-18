---
id: ui.TargetOverlayProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# TargetOverlayProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | boxes | repeated [[proto/ui.TargetBox]] | max-items: 32 |
| 2 | border_width | uint32 | <= 16 |
| 3 | hide_labels | bool | - |


## Oneofs


### _border_width

Fields: #2





## Field Notes


### boxes (#1)

The frame's target boxes, at most 32. They are DATA rather than child widget nodes: they carry their own geometry, are never laid out by LVGL, and take no part in the pointer path.


### border_width (#2)

Box stroke width in design px, explicitly present: an absent value keeps the renderer default, while a present 0 is a real stroke-less box — an annotation marked by its caption alone. A present value past 16 is refused.



