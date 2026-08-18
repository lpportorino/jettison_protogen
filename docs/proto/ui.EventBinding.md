---
id: ui.EventBinding
proto: ui/ui_ast.proto
package: ui
type: message
---

# EventBinding

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | name | string | min-len: 1, max-len: 127 |
| 2 | trigger | [[proto/ui.EventTrigger]] | defined enum value only |
| 3 | int_value | int32 | - |
| 4 | include_widget_value | bool | - |
| 5 | set_subject | string | max-len: 63 |
| 6 | set_value | int32 | - |
| 7 | toggle | bool | - |
| 8 | notify_host | bool | - |
| 9 | cmd | [[proto/ui.CmdSpec]] | - |
| 10 | cmd_by_value | repeated [[proto/ui.CmdSpec]] | max-items: 16 |




## Field Notes


### name (#1)

The event keyword, which IS the command identifier. Non-empty, and bounded at 127 for parity with `CmdSpec.command_id`: a composite command's collect events read `cmd.<Pkg>.<Command>.collect.<field>`, which exceeds 63 characters for long composites.


### trigger (#2)

Which LVGL event fires this binding. `TRIGGER_CLICKED` is the default.


### set_subject (#5)

The local subject this event mutates; empty means the event goes to the host instead. Bounded at 63 because subject names are 64-buffered everywhere they are stored, so a longer value could never resolve to a declarable subject.


### cmd_by_value (#10)

Pre-encoded command templates the widget's integer value index-selects among: the current value picks the entry, and each entry is fixed rather than slot-patched. Mutually exclusive with a patched template, and an out-of-range index emits nothing. Sixteen entries bounds the widest enum-valued control.



