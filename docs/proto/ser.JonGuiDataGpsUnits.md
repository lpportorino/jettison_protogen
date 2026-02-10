---
id: ser.JonGuiDataGpsUnits
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataGpsUnits

**Source:** `jon_shared_data_types.proto`

## Description

Specifies the coordinate format used for displaying GPS coordinates in the UI: DECIMAL_DEGREES (e.g., 40.7128), DEGREES_MINUTES_SECONDS (e.g., 40° 42' 46.08" N), or DEGREES_DECIMAL_MINUTES (e.g., 40° 42.768' N).

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_GPS_UNITS_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_GPS_UNITS_DECIMAL_DEGREES | Decimal degrees (DD) |
| 2 | JON_GUI_DATA_GPS_UNITS_DEGREES_MINUTES_SECONDS | Degrees/minutes/seconds (DMS) |
| 3 | JON_GUI_DATA_GPS_UNITS_DEGREES_DECIMAL_MINUTES | Degrees/decimal minutes (DDM) |

