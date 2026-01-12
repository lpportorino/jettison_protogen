---
id: cmd.RotaryPlatform.RotateAzimuthRelative
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuthRelative

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -180, < 180 |
| 2 | speed | double | >= 0, <= 1 |
| 3 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :fire-and-forget


### Purpose

Rotates azimuth axis by a relative offset from current position


### Related State

- [[proto/proto/ser.JonGuiDataRotaryPlatform]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.RotateAzimuthTo]]
- [[proto/proto/cmd.RotaryPlatform.HaltAzimuth]]
- [[proto/proto/cmd.RotaryPlatform.RotateElevationRelative]]



### Implementation Notes

Relative movement with speed and direction control



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`


### speed (#2)


#### Metadata

- **Semantic Type:** :raw
- **Unit:** speed
- **Display Format:** `Speed: {value}`


### direction (#3)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `Direction: {value}`



