---
id: ui.TreePatchOp
proto: ui/ui_ast.proto
package: ui
type: message
---

# TreePatchOp

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | kind | [[proto/ui.PatchOpKind]] | defined enum value only |
| 2 | target_uid | uint32 | - |
| 3 | parent_uid | uint32 | - |
| 4 | index | uint32 | - |
| 5 | node | [[proto/ui.WidgetNode]] | - |


## Oneofs


### _node

Fields: #5





## Field Notes


### kind (#1)

Which patch operation this is: insert, update props, replace, remove or move. Closed to defined `PatchOpKind` members, because an unrecognised op has no defined effect on a live tree.



