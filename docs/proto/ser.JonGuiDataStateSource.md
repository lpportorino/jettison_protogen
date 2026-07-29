---
id: ser.JonGuiDataStateSource
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataStateSource

**Source:** `jon_shared_data_types.proto`

## Description

Indicates the origin of GUI state data in the system: DAY_PIPELINE (day imaging pipeline), HEAT_PIPELINE (thermal imaging pipeline), or SYSTEM (centralized system components). Used to track which subsystem originated a state update.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_STATE_SOURCE_UNSPECIFIED | Proto3 zero default, never legitimately emitted: the sole carrier `JonGUIState.state_source` (#3) applies `defined_only` + `not_in:[0]`, so a snapshot that omits its originator does not pass validation. |
| 1 | JON_GUI_DATA_STATE_SOURCE_DAY_PIPELINE | This state snapshot was published by the day (visible-light) imaging pipeline. Every `JonGUIState` carries BOTH `frame_pts_day_ns` and `frame_pts_heat_ns`, so the timestamps cannot say which pipeline emitted a given snapshot — this field is what does. |
| 2 | JON_GUI_DATA_STATE_SOURCE_HEAT_PIPELINE | This state snapshot was published by the thermal imaging pipeline. |
| 3 | JON_GUI_DATA_STATE_SOURCE_SYSTEM | This state snapshot originated in centralised system components rather than in either camera pipeline. |

