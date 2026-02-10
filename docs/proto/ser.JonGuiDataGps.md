---
id: ser.JonGuiDataGps
proto: jon_shared_data_gps.proto
package: ser
type: message
---

# JonGuiDataGps

**Source:** `jon_shared_data_gps.proto`

## Description

Represents the complete GPS positioning state of the system, including both automatic GPS fix coordinates and manually-entered fallback coordinates, along with the current fix quality type (none, 1D, 2D, 3D, or manual mode) and operational status.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | longitude | double | >= -180, <= 180 |
| 2 | latitude | double | >= -90, <= 90 |
| 3 | altitude | double | >= -430, <= 100000 |
| 4 | manual_longitude | double | >= -180, <= 180 |
| 5 | manual_latitude | double | >= -90, <= 90 |
| 6 | manual_altitude | double | >= -430, <= 100000 |
| 7 | fix_type | [[proto/ser.JonGuiDataGpsFixType]] | defined enum value only, not in: 0 |
| 8 | use_manual | bool | - |
| 9 | timestamp | int64 | - |
| 10 | is_started | bool | - |
| 11 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

GPS position, fix type, and satellite information





### Implementation Notes

Displays GPS coordinates with fix status indicator (N/A, TIME, 2D, 3D, MAN)



## Field Notes


### longitude (#1)

Longitude in decimal degrees


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value.toFixed(6)}`


### latitude (#2)

Latitude in decimal degrees


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value.toFixed(6)}`


### altitude (#3)

Altitude in meters above sea level


#### Metadata

- **Semantic Type:** :distance
- **Unit:** m
- **Precision:** 2


### manual_longitude (#4)

Longitude in decimal degrees


### manual_latitude (#5)

Latitude in decimal degrees


#### Metadata

- **Semantic Type:** :coordinate-geo


### manual_altitude (#6)

Altitude in meters above sea level


#### Metadata

- **Semantic Type:** :distance


### fix_type (#7)

See related enum for valid values


### timestamp (#9)

Monotonic timestamp in microseconds


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** degrees
- **Precision:** 6


### is_started (#10)

GPS receiver started state


#### Metadata

- **Semantic Type:** :toggle-state
- **Unit:** degrees
- **Precision:** 6


### meteo (#11)

Local environmental sensor data from the GPS module, providing temperature, humidity, and pressure readings for ballistics calculations and system diagnostics.



