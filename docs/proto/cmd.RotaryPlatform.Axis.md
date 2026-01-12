---
id: cmd.RotaryPlatform.Axis
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# Axis

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Sends pan (azimuth) and/or tilt (elevation) axis control commands to the rotary platform, supporting multiple movement modes including absolute position, continuous rotation, and relative movement with optional speed control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | azimuth | [[proto/cmd.RotaryPlatform.Azimuth]] | - |
| 2 | elevation | [[proto/cmd.RotaryPlatform.Elevation]] | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :optimistic-visual


### Purpose

Simultaneous control of both azimuth and elevation axes


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataRotary]]



### Preconditions

- Rotary platform started


### Implementation Notes

Composite command allowing coordinated dual-axis movement, used extensively in gamepad and pan controls



