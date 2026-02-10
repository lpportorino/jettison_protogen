---
id: cmd.RotaryPlatform.ScanUpdateNode
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanUpdateNode

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Modifies an existing scan node waypoint (identified by index) with new position coordinates (azimuth and elevation), zoom levels for both day and thermal cameras, dwell time (linger), and movement speed. This allows editing waypoint parameters in a scan sequence without recreating the node.

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
- **UI Pattern:** :tabbed-config
- **Feedback:** :fire-and-forget


### Purpose

Update parameters of an existing scan pattern node


### Related State

- [[proto/ser.ScanNode]]






## Field Notes


### index (#1)


#### Metadata

- **Semantic Type:** :count


### DayZoomTableValue (#2)


#### Metadata

- **Semantic Type:** :count


### HeatZoomTableValue (#3)


#### Metadata

- **Semantic Type:** :count


### azimuth (#4)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### elevation (#5)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### linger (#6)


#### Metadata

- **Semantic Type:** :duration
- **Unit:** ms


### speed (#7)


#### Metadata

- **Semantic Type:** :raw



