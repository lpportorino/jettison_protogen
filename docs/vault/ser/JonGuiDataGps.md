---
id: ser.JonGuiDataGps
proto: jon_shared_data_gps.proto
package: ser
type: message
---

# JonGuiDataGps

**Source:** `jon_shared_data_gps.proto`

## Description

GPS state including position, altitude, fix type, and satellite count. Provides real-time location data from GPS receiver or manual override values, along with fix quality indicators.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | longitude | double | >= -180, <= 180 |
| 2 | latitude | double | >= -90, <= 90 |
| 3 | altitude | double | >= -430, <= 100000 |
| 4 | manual_longitude | double | >= -180, <= 180 |
| 5 | manual_latitude | double | >= -90, <= 90 |
| 6 | manual_altitude | double | >= -430, <= 100000 |
| 7 | fix_type | [[ser.JonGuiDataGpsFixType]] | defined enum value only, not in: 0 |
| 8 | use_manual | bool | - |
| 9 | timestamp | int64 | - |
| 10 | is_started | bool | - |

## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Update Rate:** Real-time (1-10 Hz typical)

### Purpose

Provides real-time GPS position information for the platform. UI components should display current coordinates, altitude, fix quality, and allow switching between GPS and manual position modes. Essential for targeting, navigation, and situational awareness.

### Related Commands

- [[cmd.Gps.SetManualPosition]] - Sets manual override coordinates
- [[cmd.Gps.EnableManualMode]] - Switches to manual position mode
- [[cmd.Gps.DisableManualMode]] - Switches to GPS receiver mode
- [[cmd.Gps.Start]] - Starts GPS subsystem
- [[cmd.Gps.Stop]] - Stops GPS subsystem

### Display Guidelines

Display current coordinates (latitude/longitude) with precision appropriate to fix type. Show altitude with unit indicator (meters). Display fix type with color coding (no fix = red, 2D = yellow, 3D = green, RTK = blue). Show mode indicator (GPS vs Manual). Display timestamp for data freshness. Consider map view for geographic context.

## Field Notes

### longitude (#1)

Current GPS longitude (WGS84).

#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}° {E/W}`
- **Range:** -180 to 180 (negative = West, positive = East)

### latitude (#2)

Current GPS latitude (WGS84).

#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}° {N/S}`
- **Range:** -90 to 90 (negative = South, positive = North)

### altitude (#3)

Current GPS altitude above mean sea level.

#### Metadata

- **Semantic Type:** :altitude
- **Unit:** meters
- **Precision:** 1
- **Display Format:** `{value} m MSL`
- **Range:** -430 (Dead Sea) to 100000 (beyond stratosphere)

### manual_longitude (#4)

Manual override longitude value.

#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}° {E/W}`
- **Note:** Used when use_manual is true

### manual_latitude (#5)

Manual override latitude value.

#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}° {N/S}`
- **Note:** Used when use_manual is true

### manual_altitude (#6)

Manual override altitude value.

#### Metadata

- **Semantic Type:** :altitude
- **Unit:** meters
- **Precision:** 1
- **Display Format:** `{value} m MSL`
- **Note:** Used when use_manual is true

### fix_type (#7)

GPS fix quality indicator.

#### Metadata

- **Semantic Type:** :enum
- **Display Format:** Show with color coding:
  - No Fix: Red/Error
  - 2D Fix: Yellow/Warning
  - 3D Fix: Green/OK
  - DGPS/RTK: Blue/Excellent

### use_manual (#8)

Manual position mode active (true = using manual coordinates, false = using GPS).

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "Mode: Manual/GPS" or mode indicator

### timestamp (#9)

GPS data timestamp (Unix epoch milliseconds).

#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** milliseconds
- **Display Format:** Show age (e.g., "2s ago") or absolute time
- **Note:** Use to determine data freshness

### is_started (#10)

GPS subsystem running state.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "GPS: Started/Stopped" or status indicator



