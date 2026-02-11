---
id: ser.JonGuiDataGpsFixType
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataGpsFixType

**Source:** `jon_shared_data_types.proto`

## Description

Represents the quality and type of GPS positional fix available: No Fix (no satellite lock), 1D Fix (time only), 2D Fix (lat/lon without altitude), 3D Fix (full position with altitude), and Manual Fix (user-provided coordinates). Displayed in UI as "N/A", "TIME", "2D", "3D", and "MAN".

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_GPS_FIX_TYPE_UNSPECIFIED | - |
| 1 | JON_GUI_DATA_GPS_FIX_TYPE_NONE | - |
| 2 | JON_GUI_DATA_GPS_FIX_TYPE_1D | - |
| 3 | JON_GUI_DATA_GPS_FIX_TYPE_2D | - |
| 4 | JON_GUI_DATA_GPS_FIX_TYPE_3D | - |
| 5 | JON_GUI_DATA_GPS_FIX_TYPE_MANUAL | - |

