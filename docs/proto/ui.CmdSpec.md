---
id: ui.CmdSpec
proto: ui/ui_ast.proto
package: ui
type: message
---

# CmdSpec

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | command_id | string | max-len: 127 |
| 2 | root_template | bytes | - |
| 3 | patches | repeated [[proto/ui.FieldPatch]] | max-items: 8 |
| 4 | ndc_y_sense | [[proto/ui.NdcYSense]] | defined enum value only |




## Field Notes


### command_id (#1)

The source command id this template was pre-encoded from, e.g. `cmd.RotaryPlatform.RotateToNDC`. Provenance only — the renderer never re-derives a route from it. The 127-character bound matches `EventBinding.name`, which carries the same identifier.


### patches (#3)

The slots the renderer overwrites at runtime. Bounded at eight by the widest command this vocabulary must send in one shot: the rotary scan-node commands carry seven operator-facing fields, so a form for one needs seven slots.


### ndc_y_sense (#4)

The vertical sense of this command's NDC y leaves. Required — and refused when unspecified — on any spec carrying an NDC-y slot, because the destination command's plane is not always the pointer's; meaningless and left unset on every other spec.



