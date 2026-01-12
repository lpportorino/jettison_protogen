---
id: cmd.Lrf.RefineOff
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# RefineOff

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Disables LRF refinement mode, setting the refining state to false to stop precision refinement operations on the laser rangefinder system.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Disables LRF refine mode


### Related State

- [[proto/proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/proto/cmd.Lrf.RefineOn]]



### Implementation Notes

Refine mode provides higher precision measurements



