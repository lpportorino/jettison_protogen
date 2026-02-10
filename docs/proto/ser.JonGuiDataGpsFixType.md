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
| 0 | JON_GUI_DATA_GPS_FIX_TYPE_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_GPS_FIX_TYPE_NONE | No fix |
| 2 | JON_GUI_DATA_GPS_FIX_TYPE_1D | 1D fix (time only) |
| 3 | JON_GUI_DATA_GPS_FIX_TYPE_2D | 2D fix (latitude/longitude) |
| 4 | JON_GUI_DATA_GPS_FIX_TYPE_3D | 3D fix (lat/lon/altitude) |
| 5 | JON_GUI_DATA_GPS_FIX_TYPE_MANUAL | Manual position entry |

