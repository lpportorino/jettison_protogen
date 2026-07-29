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
| 0 | JON_GUI_DATA_GPS_FIX_TYPE_UNSPECIFIED | Protobuf default zero, meaning the field was never set. Both fields typed by this enum — `ser.JonGuiDataGps.fix_type` and `ser.JonGuiDataLrf.observer_fix_type` — carry `not_in: [0]`, so a message leaving the fix type unset is rejected at the validation boundary instead of being shown as a fix. |
| 1 | JON_GUI_DATA_GPS_FIX_TYPE_NONE | Receiver reports no satellite lock: neither a position nor a time solution is available. Displayed as `N/A`. |
| 2 | JON_GUI_DATA_GPS_FIX_TYPE_1D | Time-only solution: the receiver hears enough satellites to discipline its clock but cannot solve a position, so latitude, longitude and altitude are all unusable. Displayed as `TIME`. |
| 3 | JON_GUI_DATA_GPS_FIX_TYPE_2D | Horizontal solution: latitude and longitude are solved, altitude is not. Displayed as `2D`. |
| 4 | JON_GUI_DATA_GPS_FIX_TYPE_3D | Full solution: latitude, longitude and altitude are all solved. Displayed as `3D`. |
| 5 | JON_GUI_DATA_GPS_FIX_TYPE_MANUAL | Position is operator-entered rather than satellite-derived; the coordinates in force are `ser.JonGuiDataGps`'s `manual_longitude`, `manual_latitude` and `manual_altitude` fields rather than the satellite-derived triple. Displayed as `MAN`. |

