---
id: ui.TargetBox
proto: ui/ui_ast.proto
package: ui
type: message
---

# TargetBox

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | x | int32 | - |
| 2 | y | int32 | - |
| 3 | w | int32 | - |
| 4 | h | int32 | - |
| 5 | label | string | max-len: 31 |
| 6 | color | [[proto/ui.Color]] | - |




## Field Notes


### label (#5)

Caption drawn inside the box's top-left corner; empty means no caption. Bounded at 31 characters, which is what fits over a detection. It takes its text colour and font from the overlay node rather than from a style group of its own.



