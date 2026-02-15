---
id: ser.ScanNode
proto: jon_shared_data_rotary.proto
package: ser
type: message
---

# ScanNode

**Source:** `jon_shared_data_rotary.proto`

## Description

Represents a single waypoint within a rotary platform scanning pattern, containing positional data (azimuth and elevation angles), camera zoom table positions for both day and thermal cameras, and transition parameters (linger time at the waypoint and speed to the next node). Used across frontend scanning pattern editors, backend scan APIs, and embedded device controllers to define and execute multi-point scanning sequences.

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

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Represents a single node in a scanning pattern with position and timing parameters



### Related Commands

- [[proto/cmd.RotaryPlatform.ScanUpdateNode]]
- [[proto/cmd.RotaryPlatform.ScanAddNode]]
- [[proto/cmd.RotaryPlatform.ScanDeleteNode]]





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
- **Unit:** °
- **Precision:** 2


### elevation (#5)

Elevation angle in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 2


### linger (#6)

Dwell time at this scan node in seconds


#### Metadata

- **Semantic Type:** :duration
- **Unit:** s
- **Precision:** 1


### speed (#7)

Movement speed (0.0=stopped, 1.0=maximum)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3



