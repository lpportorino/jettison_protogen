---
id: cmd.Lrf.Stop
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# Stop

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Stops the LRF (Laser Rangefinder) device by setting its operational state to inactive, transitioning the device from active to stopped operation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stop laser rangefinder operation


### Related State

- [[proto/ser.JonGuiDataLrf]] - `is_started` flag reflects current state


### Related Commands

- [[proto/cmd.Lrf.Start]]


### Preconditions

- LRF must be started


### Implementation Notes

Frontend function `lrfStop()` in `cmdLRF.ts` sends this command. Lifecycle command pair with Start. When executed, the device stops active operations and powers down the laser hardware.



