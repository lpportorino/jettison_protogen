---
id: cmd.RotaryPlatform.ScanAddNode
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanAddNode

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Adds a waypoint node to the rotary platform scan path, specifying position (azimuth/elevation), camera zoom levels for both day and thermal sensors, dwell time, and movement speed.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | index | int32 | >= 0 |
| 2 | DayZoomTableValue | int32 | >= 0 |
| 3 | HeatZoomTableValue | int32 | >= 0 |
| 4 | azimuth | double | >= 0, < 360 |
| 5 | elevation | double | >= -90, <= 90 |
| 6 | linger | double | >= 0 |
| 7 | speed | double | > 0, <= 1 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Adds a new waypoint node to scan pattern



### Related Commands

- [[proto/proto/cmd.RotaryPlatform.ScanUpdateNode]]
- [[proto/proto/cmd.RotaryPlatform.ScanDeleteNode]]





## Field Notes


### index (#1)


#### Metadata

- **Semantic Type:** :count


### DayZoomTableValue (#2)


#### Metadata

- **Semantic Type:** :raw


### HeatZoomTableValue (#3)


#### Metadata

- **Semantic Type:** :raw


### azimuth (#4)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2


### elevation (#5)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2


### linger (#6)


#### Metadata

- **Semantic Type:** :duration
- **Unit:** s
- **Precision:** 1


### speed (#7)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 2



