---
id: ui.FieldPatch
proto: ui/ui_ast.proto
package: ui
type: message
---

# FieldPatch

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | byte_offset | uint32 | - |
| 2 | byte_width | uint32 | - |
| 3 | kind | [[proto/ui.PatchKind]] | defined enum value only |
| 4 | wire_scale | sint32 | - |
| 5 | subject | string | max-len: 63 |
| 6 | encoding | [[proto/ui.PatchEncoding]] | defined enum value only |




## Field Notes


### kind (#3)

What the renderer writes into this slot: an NDC coordinate, a gesture step, the emitting widget's own value, or the current int of a named subject. Closed to defined `PatchKind` members.


### subject (#5)

The local subject whose current int this slot reads. Required when `kind` is `PATCH_KIND_SUBJECT_VALUE` and empty for every other kind; the renderer refuses both violations. Bounded at 63 like every other subject reference, so a name legal to declare is always legal to reference here.


### encoding (#6)

How the patcher writes a value-sourced slot: a padded varint, or little-endian IEEE-754 at four or eight bytes. Orthogonal to `kind`, because one integer source can target three different wire shapes.



