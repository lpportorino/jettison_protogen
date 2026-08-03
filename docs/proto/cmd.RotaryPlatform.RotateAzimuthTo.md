---
id: cmd.RotaryPlatform.RotateAzimuthTo
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuthTo

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Commands the rotary platform to rotate its azimuth axis to a specified target angle at a given speed and direction, allowing controlled positioning to a target heading.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target_value | double | >= 0, < 360 |
| 2 | speed | double | >= 0, <= 1 |
| 3 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :pending-timeout


### Purpose

Rotates azimuth to target angle at specified speed


### Related State

- [[proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/cmd.RotaryPlatform.SetPlatformAzimuth]]





## Field Notes


### target_value (#1)

Target position value


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2


### speed (#2)

Movement speed (0.0=stopped, 1.0=maximum)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0


### direction (#3)

Rotation direction


#### Metadata

- **Semantic Type:** :enum-label



