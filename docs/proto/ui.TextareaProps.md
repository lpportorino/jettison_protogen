---
id: ui.TextareaProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# TextareaProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | placeholder | string | max-len: 255 |
| 2 | max_length | uint32 | - |
| 3 | one_line | bool | - |
| 4 | password_mode | bool | - |




## Field Notes


### placeholder (#1)

Placeholder text shown while the textarea is empty. The 255-character bound matches `WidgetNode.text`.



