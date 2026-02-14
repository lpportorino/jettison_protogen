---
id: cmd.HeatCamera.ShiftDDE
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ShiftDDE

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Incremental adjustment command for thermal camera DDE (Digital Detail Enhancement) level. Accepts positive or negative shift values between -100 and 100, typically used with keyboard shortcuts that shift the DDE level by ±15 increments.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | int32 | >= -100, <= 100 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Adjust DDE (Digital Detail Enhancement) level incrementally for heat camera


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.SetDDELevel]]
- [[proto/cmd.HeatCamera.EnableDDE]]
- [[proto/cmd.HeatCamera.DisableDDE]]



### Preconditions

- DDE must be enabled (via `EnableDDE`)
- For increment (+15): current DDE level must be <= 240
- For decrement (-15): current DDE level must be >= 15


### Implementation Notes

Used with keyboard shortcuts to shift DDE level by +/-15. The frontend enforces boundary checking before sending the command to prevent level overflow/underflow. DDE level range is 0-255.



## Field Notes


### value (#1)

Value (-100 to 100)


#### Metadata

- **Semantic Type:** :raw
- **Display Format:** `Shift value (positive or negative)`



