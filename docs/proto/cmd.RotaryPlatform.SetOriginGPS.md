---
id: cmd.RotaryPlatform.SetOriginGPS
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetOriginGPS

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Establishes the GPS reference/origin point for the rotary platform by specifying latitude, longitude, and altitude coordinates. This origin point is used as the baseline reference for subsequent GPS-based operations like RotateToGPS.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | latitude | double | >= -90, <= 90 |
| 2 | longitude | double | >= -180, < 180 |
| 3 | altitude | double | >= -430, <= 100000 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Sets the GPS origin point for rotary platform coordinate calculations


### Related State

- [[proto/ser.JonGuiDataGps]]


### Related Commands

- [[proto/cmd.RotaryPlatform.RotateToGPS]]



### Implementation Notes

Establishes coordinate system origin for GPS-based targeting



## Field Notes


### latitude (#1)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}°`


### longitude (#2)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6
- **Display Format:** `{value}°`


### altitude (#3)


#### Metadata

- **Semantic Type:** :distance
- **Unit:** m
- **Precision:** 2
- **Display Format:** `{value} m`



