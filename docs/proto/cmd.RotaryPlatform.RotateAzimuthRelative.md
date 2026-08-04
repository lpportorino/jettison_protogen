---
id: cmd.RotaryPlatform.RotateAzimuthRelative
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuthRelative

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Rotates the rotary platform's azimuth (horizontal orientation) by a relative offset from its current position at a specified speed and direction. The offset value ranges from -180 to 180 degrees with clockwise or counter-clockwise movement.

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

- [[proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/cmd.RotaryPlatform.RotateAzimuthTo]]
- [[proto/cmd.RotaryPlatform.HaltAzimuth]]
- [[proto/cmd.RotaryPlatform.RotateElevationRelative]]



### Implementation Notes

Relative movement with speed and direction control



## Field Notes


### value (#1)

Angle value in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`


### speed (#2)

Movement speed (0.0=stopped, 1.0=maximum)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`


### direction (#3)

Rotation direction


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `Direction: {value}`



