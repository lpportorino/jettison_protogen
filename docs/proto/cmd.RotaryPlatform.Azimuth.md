---
id: cmd.RotaryPlatform.Azimuth
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# Azimuth

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Container command for controlling azimuth (horizontal) axis movement of a rotary platform, supporting absolute positioning, continuous rotation, relative adjustments, and halt operations.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | set_value | [[proto/cmd.RotaryPlatform.SetAzimuthValue]] | - |
| 2 | rotate_to | [[proto/cmd.RotaryPlatform.RotateAzimuthTo]] | - |
| 3 | rotate | [[proto/cmd.RotaryPlatform.RotateAzimuth]] | - |
| 4 | relative | [[proto/cmd.RotaryPlatform.RotateAzimuthRelative]] | - |
| 5 | relative_set | [[proto/cmd.RotaryPlatform.RotateAzimuthRelativeSet]] | - |
| 6 | halt | [[proto/cmd.RotaryPlatform.HaltAzimuth]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6




## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :fire-and-forget


### Purpose

Container for azimuth axis control commands


### Related State

- [[proto/proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/proto/cmd.RotaryPlatform.Azimuth.RotateTo]]
- [[proto/proto/proto/cmd.RotaryPlatform.Azimuth.Rotate]]
- [[proto/proto/proto/cmd.RotaryPlatform.Azimuth.Halt]]



### Implementation Notes

Nested message containing all azimuth movement commands



