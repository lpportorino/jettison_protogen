---
id: ui.DropdownProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# DropdownProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | options | string | max-len: 1023 |
| 2 | selected | uint32 | - |
| 3 | direction | [[proto/ui.Dir]] | defined enum value only |
| 4 | option_values | repeated int32 | max-items: 16 |




## Field Notes


### options (#1)

The option labels as one newline-separated string, the form `lv_dropdown_set_options` takes. The 1023-character bound covers the whole list.


### direction (#3)

Which way the option list opens, direct-cast to `lv_dir_t`.


### option_values (#4)

Per-option device enum VALUES, in the same order as the `options` label list. A value-driven state bind index-selects the option whose value equals the subject int, which is what fixes the enum-number-as-index off-by-one when options drop unspecified or excluded values. Empty when the dropdown carries no enum-value bind.



