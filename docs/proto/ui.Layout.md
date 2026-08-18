---
id: ui.Layout
proto: ui/ui_ast.proto
package: ui
type: message
---

# Layout

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | flow | [[proto/ui.FlexFlow]] | defined enum value only |
| 2 | main_place | [[proto/ui.FlexAlign]] | defined enum value only |
| 3 | cross_place | [[proto/ui.FlexAlign]] | defined enum value only |
| 4 | track_place | [[proto/ui.FlexAlign]] | defined enum value only |




## Field Notes


### flow (#1)

Flex flow direction, direct-cast to `lv_flex_flow_t`.


### main_place (#2)

Alignment of the items along the main axis (`lv_obj_set_flex_align`).


### cross_place (#3)

Alignment of each item across the flow axis.


### track_place (#4)

Alignment of the tracks themselves once the flow wraps.



