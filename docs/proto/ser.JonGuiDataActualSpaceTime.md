---
id: ser.JonGuiDataActualSpaceTime
proto: jon_shared_data_actual_space_time.proto
package: ser
type: message
---

# JonGuiDataActualSpaceTime

**Source:** `jon_shared_data_actual_space_time.proto`

## Description

Encapsulates real-time spatial position and temporal information of the system, containing three-dimensional attitude angles (azimuth, elevation, bank), geographic coordinates (latitude, longitude, altitude), and a timestamp. Displayed across multiple UI widgets including the azimuth compass, altitude scale, and time widget.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | azimuth | double | >= 0, < 360 |
| 2 | elevation | double | >= -90, <= 90 |
| 3 | bank | double | >= -180, < 180 |
| 4 | latitude | double | >= -90, <= 90 |
| 5 | longitude | double | >= -180, < 180 |
| 6 | altitude | double | - |
| 7 | timestamp | int64 | >= 0 |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Current spatial positioning and orientation of the platform





### Implementation Notes

Real-time status of platform position including azimuth, elevation, bank, GPS coordinates, and timestamp. Critical for targeting and navigation.



## Field Notes


### azimuth (#1)

Azimuth angle in degrees (0=North, clockwise)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`


### elevation (#2)

Elevation angle in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`


### bank (#3)

Bank/roll angle in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`


### latitude (#4)

Latitude in decimal degrees


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6


### longitude (#5)

Longitude in decimal degrees


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6


### altitude (#6)

Altitude in meters above sea level


#### Metadata

- **Semantic Type:** :distance
- **Unit:** meters
- **Precision:** 1
- **Display Format:** `{value}m`


### timestamp (#7)

Monotonic timestamp in microseconds


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds



