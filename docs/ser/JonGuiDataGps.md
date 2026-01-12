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


### Purpose

Provides real-time GPS position information for the platform. UI components should display current coordinates, altitude, fix quality, and allow switching between GPS and manual position modes. Essential for targeting, navigation, and situational awareness.



### Related Commands

- [[cmd.Gps.SetManualPosition]]
- [[cmd.Gps.EnableManualMode]]
- [[cmd.Gps.DisableManualMode]]
- [[cmd.Gps.Start]]
- [[cmd.Gps.Stop]]





## Field Notes


### longitude (#1)

Current GPS longitude (WGS84).


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}° {E/W}`


### latitude (#2)

Current GPS latitude (WGS84).


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}° {N/S}`


### altitude (#3)

Current GPS altitude above mean sea level.


#### Metadata

- **Semantic Type:** :distance
- **Unit:** meters
- **Precision:** 1
- **Display Format:** `{value} m MSL`


### manual_longitude (#4)

Manual override longitude value.


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}° {E/W}`


### manual_latitude (#5)

Manual override latitude value.


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}° {N/S}`


### manual_altitude (#6)

Manual override altitude value.


#### Metadata

- **Semantic Type:** :distance
- **Unit:** meters
- **Precision:** 1
- **Display Format:** `{value} m MSL`


### fix_type (#7)

GPS fix quality indicator.


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `Show with color coding:`


### use_manual (#8)

Manual position mode active (true = using manual coordinates, false = using GPS).


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Mode: Manual/GPS&quot; or mode indicator`


### timestamp (#9)

GPS data timestamp (Unix epoch milliseconds).


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** milliseconds
- **Display Format:** `Show age (e.g., &quot;2s ago&quot;) or absolute time`


### is_started (#10)

GPS subsystem running state.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;GPS: Started/Stopped&quot; or status indicator`



