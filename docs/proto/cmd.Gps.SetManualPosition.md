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
- **UI Pattern:** :tabbed-config
- **Feedback:** :fire-and-forget


### Purpose

Set manual GPS position override



### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.Gps.SetUseManualPosition]]





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
- **Unit:** meters
- **Precision:** 1



