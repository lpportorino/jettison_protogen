---
id: ser.JonGuiDataExtBatStatus
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataExtBatStatus

**Source:** `jon_shared_data_types.proto`

## Description

Represents the operational state of an external battery pack, indicating whether the battery is actively charging, discharging, or performing cell balancing. Displayed in the UI with color-coded indicators and pulsing animations for charging/balancing states.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_EXT_BAT_STATUS_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_EXT_BAT_STATUS_CHARGING | Battery charging |
| 2 | JON_GUI_DATA_EXT_BAT_STATUS_DISCHARGING | Battery discharging |
| 3 | JON_GUI_DATA_EXT_BAT_STATUS_BALANCING | Cell balancing |

