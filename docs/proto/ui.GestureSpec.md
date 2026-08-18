---
id: ui.GestureSpec
proto: ui/ui_ast.proto
package: ui
type: message
---

# GestureSpec

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | kind | [[proto/ui.GestureKind]] | defined enum value only |
| 2 | cmd | [[proto/ui.CmdSpec]] | - |
| 3 | delta_sign | [[proto/ui.GestureDeltaSign]] | defined enum value only |




## Field Notes


### kind (#1)

Which gesture this template answers. The host recognizer matches a decision to the entry whose kind equals its own, so an unrecognised member would leave the decision unanswerable — hence closed to defined `GestureKind` members.


### delta_sign (#3)

Which sign of the gesture's step this entry answers. Two entries may share a `kind` only as the positive/negative pair; every other repeat is refused at load, because a tie broken on repeated-field order would make element order a contract this message does not state.



