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


### Purpose

Represents a single node in a scanning pattern with position and timing parameters



### Related Commands

- [[proto/cmd.RotaryPlatform.ScanUpdateNode]]
- [[proto/cmd.RotaryPlatform.ScanAddNode]]
- [[proto/cmd.RotaryPlatform.ScanDeleteNode]]





## Field Notes


### index (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### DayZoomTableValue (#2)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### HeatZoomTableValue (#3)


#### Metadata

- **Semantic Type:** :duration
- **Unit:** ms


### azimuth (#4)


#### Metadata

- **Semantic Type:** :raw


### elevation (#5)


#### Metadata

- **Semantic Type:** :count


### linger (#6)


#### Metadata

- **Semantic Type:** :count



