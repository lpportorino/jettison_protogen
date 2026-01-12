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
| 0 | JON_GUI_DATA_STATE_SOURCE_UNSPECIFIED | - |
| 1 | JON_GUI_DATA_STATE_SOURCE_DAY_PIPELINE | - |
| 2 | JON_GUI_DATA_STATE_SOURCE_HEAT_PIPELINE | - |
| 3 | JON_GUI_DATA_STATE_SOURCE_SYSTEM | - |

