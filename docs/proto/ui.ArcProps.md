---
id: ui.ArcProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# ArcProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start_angle | uint32 | <= 360 |
| 2 | end_angle | uint32 | <= 360 |
| 3 | bg_start_angle | uint32 | <= 360 |
| 4 | bg_end_angle | uint32 | <= 360 |
| 5 | rotation | int32 | - |
| 6 | mode | [[proto/ui.ArcMode]] | defined enum value only |
| 7 | min_value | int32 | - |
| 8 | max_value | int32 | - |
| 9 | value | int32 | - |




## Field Notes


### start_angle (#1)

Start of the arc's foreground sweep, in degrees. Bounded at 360 because it is an absolute angle on the circle rather than a delta.


### end_angle (#2)

End of the foreground sweep, in the same degree frame as `start_angle`.


### bg_start_angle (#3)

Start of the background sweep — the track drawn behind the indicator.


### bg_end_angle (#4)

End of the background sweep, in the same degree frame as `bg_start_angle`.


### mode (#6)

How the indicator grows with the value: normal, reverse, or symmetrical about the range midpoint. Closed to defined `ArcMode` members — an unrecognised mode has no LVGL behaviour to fall back on.



