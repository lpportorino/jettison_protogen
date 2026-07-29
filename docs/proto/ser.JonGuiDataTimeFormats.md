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
| 0 | JON_GUI_DATA_TIME_FORMAT_UNSPECIFIED | Protobuf default zero, meaning no time format was selected. It is a placeholder for an unset field, never a renderable format; no message in the current proto surface carries this enum, so nothing validates it away. |
| 1 | JON_GUI_DATA_TIME_FORMAT_H_M_S | Render the time of day only — hours, minutes and seconds — with no date component. The compact form, for a readout where the day is already known from context. |
| 2 | JON_GUI_DATA_TIME_FORMAT_Y_m_D_H_M_S | Render the full timestamp: calendar date then time of day, year first, then month, day, hours, minutes and seconds. Big-endian date ordering, so lexical sort equals chronological sort. |

