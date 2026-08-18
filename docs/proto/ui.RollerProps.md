---
id: ui.RollerProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# RollerProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | options | string | max-len: 511 |
| 2 | selected | uint32 | - |
| 3 | visible_row_count | uint32 | - |
| 4 | mode | [[proto/ui.RollerMode]] | defined enum value only |




## Field Notes


### options (#1)

The roller's option labels as one newline-separated string. The 511-character bound covers the whole list.


### mode (#4)

Whether the roller stops at its ends or wraps around (`lv_roller_set_options` mode). Closed to defined `RollerMode` members.



