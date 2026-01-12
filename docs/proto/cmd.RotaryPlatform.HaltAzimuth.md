---
id: cmd.RotaryPlatform.HaltAzimuth
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# HaltAzimuth

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Stops azimuth (horizontal rotation) movement of the rotary platform on the device, typically used to halt rotational motion along the yaw axis independently from elevation control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Immediately stops azimuth axis movement


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/proto/proto/proto/cmd.RotaryPlatform.Halt]]



### Implementation Notes

Emergency stop for azimuth only



