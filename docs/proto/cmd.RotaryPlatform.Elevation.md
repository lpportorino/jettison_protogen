---
id: cmd.RotaryPlatform.Elevation
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# Elevation

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Container message for elevation (tilt) axis control commands on a rotary platform, supporting operations such as setting position, rotating to a target, continuous rotation, relative movement, and halt commands.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | set_value | [[proto/cmd.RotaryPlatform.SetElevationValue]] | - |
| 2 | rotate_to | [[proto/cmd.RotaryPlatform.RotateElevationTo]] | - |
| 3 | rotate | [[proto/cmd.RotaryPlatform.RotateElevation]] | - |
| 4 | relative | [[proto/cmd.RotaryPlatform.RotateElevationRelative]] | - |
| 5 | relative_set | [[proto/cmd.RotaryPlatform.RotateElevationRelativeSet]] | - |
| 6 | halt | [[proto/cmd.RotaryPlatform.HaltElevation]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6




## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :fire-and-forget


### Purpose

Container for elevation axis commands


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataRotary]]




### Implementation Notes

Submessage containing elevation movement commands



