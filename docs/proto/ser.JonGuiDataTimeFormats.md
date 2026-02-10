---
id: ser.JonGuiDataTimeFormats
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataTimeFormats

**Source:** `jon_shared_data_types.proto`

## Description

Defines time display format options for the GUI system: H_M_S displays time as Hours:Minutes:Seconds, while Y_m_D_H_M_S displays full date and time as Year-Month-Day Hours:Minutes:Seconds. Used to configure how timestamps are rendered in the UI.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_TIME_FORMAT_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_TIME_FORMAT_H_M_S | Hours:Minutes:Seconds |
| 2 | JON_GUI_DATA_TIME_FORMAT_Y_m_D_H_M_S | Year/Month/Day Hours:Minutes:Seconds |

