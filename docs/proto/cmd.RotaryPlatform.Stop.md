---
id: cmd.RotaryPlatform.Stop
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# Stop

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.Start]]
- [[proto/proto/cmd.RotaryPlatform.Halt]]



### Implementation Notes

Lifecycle command - disables the rotary system



