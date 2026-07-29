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
| 0 | JON_GUI_DATA_GPS_UNITS_UNSPECIFIED | Protobuf default zero, meaning no coordinate format was selected. It is a placeholder for an unset field, never a renderable format; no message in the current proto surface carries this enum, so nothing validates it away. |
| 1 | JON_GUI_DATA_GPS_UNITS_DECIMAL_DEGREES | Render each coordinate as one signed decimal number of degrees (DD), e.g. `40.7128`. This is the form arithmetic and storage use directly — no minute or second subdivision, and the sign carries the hemisphere. |
| 2 | JON_GUI_DATA_GPS_UNITS_DEGREES_MINUTES_SECONDS | Render each coordinate as whole degrees, whole arc-minutes and decimal arc-seconds with a hemisphere letter (DMS), e.g. `40° 42' 46.08" N`. One arc-minute is 1/60 of a degree and one arc-second 1/3600. |
| 3 | JON_GUI_DATA_GPS_UNITS_DEGREES_DECIMAL_MINUTES | Render each coordinate as whole degrees plus decimal arc-minutes with a hemisphere letter (DDM), e.g. `40° 42.768' N`. This is the subdivision NMEA 0183 position sentences carry natively, so it needs no conversion when the receiver's raw output is echoed. |

