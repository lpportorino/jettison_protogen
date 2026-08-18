---
id: ui.StyleVariant
proto: ui/ui_ast.proto
package: ui
type: message
---

# StyleVariant

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | variant_index | uint32 | <= 7 |
| 2 | properties | repeated [[proto/ui.StyleProperty]] | - |




## Field Notes


### variant_index (#1)

Which composite variant this fully-resolved prop set is for: `breakpoint_tier * 2 + theme_dark`, hence the range 0-7. Index 0 is the base and is always present.



