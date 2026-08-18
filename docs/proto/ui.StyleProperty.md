---
id: ui.StyleProperty
proto: ui/ui_ast.proto
package: ui
type: message
---

# StyleProperty

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | type | [[proto/ui.StylePropertyType]] | defined enum value only |
| 2 | uint_value | uint32 | - |
| 3 | int_value | int32 | - |
| 4 | color_value | [[proto/ui.Color]] | - |
| 5 | string_value | string | max-len: 63 |
| 6 | shadow_value | [[proto/ui.ShadowBundle]] | - |


## Oneofs


### value

Fields: #2, #3, #4, #5, #6





## Field Notes


### type (#1)

Which LVGL style property this entry sets. Closed to defined `StylePropertyType` members: an unrecognised property names no target to apply the value to.


### string_value (#5)

The string arm of the value oneof — a font C symbol name or an image source path. Bounded at 63 characters, which is what a symbol name needs.



