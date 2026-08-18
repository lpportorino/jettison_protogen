---
id: ui.StyleGroup
proto: ui/ui_ast.proto
package: ui
type: message
---

# StyleGroup

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | state_selector | uint32 | - |
| 2 | variants | repeated [[proto/ui.StyleVariant]] | min-items: 1, max-items: 8 |




## Field Notes


### variants (#2)

The group's composite variants. At least one, because the base (variant index 0) is always present and emitted first, and at most eight — one per composite index, which is the breakpoint tier crossed with the theme.



