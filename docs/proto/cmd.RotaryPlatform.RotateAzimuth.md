---
id: cmd.RotaryPlatform.RotateAzimuth
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuth

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | speed | double | >= 0, <= 1 |
| 2 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :fire-and-forget


### Purpose

Continuously rotate azimuth axis at specified speed and direction



### Related Commands

- [[proto/proto/cmd.RotaryPlatform.HaltAzimuth]]
- [[proto/proto/cmd.RotaryPlatform.RotateAzimuthTo]]





## Field Notes


### speed (#1)


#### Metadata

- **Semantic Type:** :raw


### direction (#2)


#### Metadata

- **Semantic Type:** :enum-label



