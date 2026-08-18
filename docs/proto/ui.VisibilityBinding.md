---
id: ui.VisibilityBinding
proto: ui/ui_ast.proto
package: ui
type: message
---

# VisibilityBinding

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | subject | string | min-len: 1, max-len: 63 |
| 2 | ref_value | int32 | - |
| 3 | compare | [[proto/ui.CompareOp]] | defined enum value only |




## Field Notes


### subject (#1)

The subject this comparison observes. Non-empty and bounded at 63, like every other subject reference.


### compare (#3)

The comparison operator applied against the reference value. `EQ` is the default.



