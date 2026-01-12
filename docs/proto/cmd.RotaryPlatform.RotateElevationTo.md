---
id: cmd.RotaryPlatform.RotateElevationTo
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateElevationTo

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Commands the rotary platform to rotate its elevation axis to an absolute target angle (between -90 and 90 degrees) at a specified speed.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target_value | double | >= -90, <= 90 |
| 2 | speed | double | >= 0, <= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Rotates elevation axis to absolute target angle


### Related State

- [[proto/proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/proto/cmd.RotaryPlatform.Elevation.SetValue]]
- [[proto/proto/proto/cmd.RotaryPlatform.Elevation.Halt]]





## Field Notes


### target_value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2


### speed (#2)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 2



