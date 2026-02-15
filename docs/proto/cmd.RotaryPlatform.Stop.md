---
id: cmd.RotaryPlatform.Stop
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# Stop

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Stops rotary platform motion and disables motor control, shutting down the rotary subsystem entirely. Unlike Halt which immediately freezes motion while keeping the system active and responsive, Stop is a lifecycle command that fully disables the rotary platform.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stop rotary platform motion and disable motor control


### Related State

- [[proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/cmd.RotaryPlatform.Start]]
- [[proto/cmd.RotaryPlatform.Halt]]



### Implementation Notes

Lifecycle command - disables the rotary system



