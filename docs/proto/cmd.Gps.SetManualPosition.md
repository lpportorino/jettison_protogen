---
id: cmd.Gps.SetManualPosition
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# SetManualPosition

**Source:** `jon_shared_cmd_gps.proto`

## Description

Sets a manual GPS position override with specified latitude, longitude, and altitude coordinates. This position is used when manual position mode is enabled via SetUseManualPosition, allowing the system to operate with a fixed location when GPS signal is unavailable or for testing purposes.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | latitude | double | >= -90, <= 90 |
| 2 | longitude | double | >= -180, < 180 |
| 3 | altitude | double | >= -430, <= 100000 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :state-machine-menu <!-- Keyboard-driven transient overlay (jonTransientGpsOverlay) -->
- **Feedback:** :fire-and-forget


### Purpose

Set manual GPS position override for use when GPS signal is unavailable or for testing purposes. The position is applied when manual mode is enabled via SetUseManualPosition.


### Related State

- [[proto/ser.JonGuiDataGps]] - Contains manual_latitude, manual_longitude, manual_altitude fields that store these values


### Related Commands

- [[proto/cmd.Gps.SetUseManualPosition]] - Enables/disables manual position mode





## Field Notes


### latitude (#1)

Latitude in decimal degrees


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6


### longitude (#2)

Longitude in decimal degrees


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6


### altitude (#3)

Altitude in meters above sea level


#### Metadata

- **Semantic Type:** :distance
- **Unit:** meters
- **Precision:** 1



