---
id: cmd.RotaryPlatform.RotateToGPS
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateToGPS

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Commands the rotary platform to rotate and point toward a specified GPS coordinate location, determined by latitude, longitude, and altitude values provided from user interaction with an interactive globe map.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | latitude | double | >= -90, <= 90 |
| 2 | longitude | double | >= -180, < 180 |
| 3 | altitude | double | >= -430, <= 100000 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Rotates platform to point at GPS coordinates




### Preconditions

- Rotary platform must be started
- GPS origin must be set




## Field Notes


### latitude (#1)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6


### longitude (#2)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees
- **Precision:** 6


### altitude (#3)


#### Metadata

- **Semantic Type:** :distance
- **Unit:** m
- **Precision:** 2



