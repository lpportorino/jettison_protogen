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

Zero-based node index


#### Metadata

- **Semantic Type:** :count


### DayZoomTableValue (#2)

Day camera zoom table index for this scan node


#### Metadata

- **Semantic Type:** :count


### HeatZoomTableValue (#3)

Heat camera zoom table index for this scan node


#### Metadata

- **Semantic Type:** :count


### azimuth (#4)

Azimuth angle in degrees (0=North, clockwise)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### elevation (#5)

Elevation angle in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### linger (#6)

Dwell time at this scan node in seconds


#### Metadata

- **Semantic Type:** :duration
- **Unit:** s


### speed (#7)

Movement speed (0.0=stopped, 1.0=maximum)


#### Metadata

- **Semantic Type:** :normalized



