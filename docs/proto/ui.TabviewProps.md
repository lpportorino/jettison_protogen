---
id: ui.TabviewProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# TabviewProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | tab_names | repeated string | max-items: 8, each string: max-len: 31 |
| 2 | tab_bar_size | int32 | - |
| 3 | active_index | uint32 | - |
| 4 | tab_bar_position | [[proto/ui.Dir]] | defined enum value only |
| 5 | tab_bar_pad_left | int32 | - |


## Oneofs


### _tab_bar_size

Fields: #2





## Field Notes


### tab_names (#1)

Tab names, one per content child of the tabview node: child i of the node's regular children becomes tab i's page content, and children flagged `in_tab_bar` are excluded from the zip and go to the tab bar instead. At most eight tabs, and each name at most 31 characters — the element bound is what keeps one long name from consuming the whole tab bar.


### tab_bar_position (#4)

Tab bar placement, direct-cast to `lv_dir_t` (parity-gated). `DIR_NONE` keeps the LVGL default, which is top.



