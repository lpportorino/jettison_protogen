---
id: cmd.RotaryPlatform.Start
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# Start

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Initializes the rotary platform subsystem by triggering a PING command to test the connection and discover the hardware address. Once the PING ACK is received, the system begins querying the platform's current state and transitions from initialization mode to operational readiness.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.Stop]]
- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.Halt]]


### Preconditions



### Implementation Notes

No parameters required. Basic lifecycle control.



