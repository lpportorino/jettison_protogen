---
id: cmd.Lrf.Start
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# Start

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Initializes and powers on the Laser Range Finder (LRF) device hardware by sending a startup command to the LRF UART control interface.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Starts the laser range finder module


### Related State

- [[proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/cmd.Lrf.Stop]]
- [[proto/cmd.Lrf.Measure]]


### Preconditions

- System powered on


### Implementation Notes

Frontend function `lrfStart()` in `cmdLRF.ts` sends this command. Lifecycle command pair with Stop. When executed, the device initializes hardware and begins accepting ranging commands. The `is_started` flag in `ser.JonGuiDataLrf` reflects the current state.



