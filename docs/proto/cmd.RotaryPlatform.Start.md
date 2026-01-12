---
id: cmd.RotaryPlatform.Start
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# Start

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Starts the rotary platform subsystem


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.Stop]]
- [[proto/proto/cmd.RotaryPlatform.Halt]]


### Preconditions



### Implementation Notes

No parameters required. Basic lifecycle control.



