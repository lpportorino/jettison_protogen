---
id: ser.ScanNode
proto: jon_shared_data_rotary.proto
package: ser
type: message
---

# ScanNode

**Source:** `jon_shared_data_rotary.proto`

## Description

*No description yet.*

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

- [[proto/proto/cmd.RotaryPlatform.ScanUpdateNode]]
- [[proto/proto/cmd.RotaryPlatform.ScanAddNode]]
- [[proto/proto/cmd.RotaryPlatform.ScanDeleteNode]]





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



