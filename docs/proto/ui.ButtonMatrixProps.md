---
id: ui.ButtonMatrixProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# ButtonMatrixProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | map_str | string | max-len: 1023 |
| 2 | one_check | bool | - |




## Field Notes


### map_str (#1)

The button-matrix map as a single string: button labels in row order, with a newline separator starting a new row. The 1023-character bound covers the whole matrix — every label and every separator together.



