---
id: cmd.RotaryPlatform.RotateElevationRelative
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateElevationRelative

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Rotates the rotary platform's elevation axis by a relative amount from its current position at a specified speed and direction. The value parameter specifies the relative elevation angle change (-90 to 90 degrees).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -90, <= 90 |
| 2 | speed | double | >= 0, <= 1 |
| 3 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :press-accelerating
- **Feedback:** :optimistic-visual


### Purpose

Rotate platform elevation relative to current position with specified speed and direction


### Related State

- [[proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/cmd.RotaryPlatform.RotateElevationTo]]
- [[proto/cmd.RotaryPlatform.HaltElevation]]
- [[proto/cmd.RotaryPlatform.SetElevationValue]]


### Preconditions

- Rotary platform must be started




## Field Notes


### value (#1)

Value (-90 to 90)


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
- **Precision:** 2
- **Display Format:** `{value * 100}%`


### direction (#3)

Rotation direction


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `{direction}`



