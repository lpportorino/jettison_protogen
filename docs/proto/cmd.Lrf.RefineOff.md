---
id: cmd.Lrf.RefineOff
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# RefineOff

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Disables LRF refine mode, stopping precision targeting adjustments. When sent, the `isRefining` state flag transitions to false, exiting fine-grained control mode for the laser rangefinder system.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Disables LRF refine mode for precision measurements


### Related State

- [[proto/ser.JonGuiDataLrf]] - `isRefining` field indicates current refine mode state


### Related Commands

- [[proto/cmd.Lrf.RefineOn]] - Toggle pair counterpart that enables refine mode
- [[proto/cmd.Lrf.Measure]] - Primary LRF measurement command


### Preconditions

- LRF must be started
- Refine mode must be currently active (`isRefining` = true)


### Implementation Notes

Forms a toggle pair with RefineOn. In the frontend UI, the refine button only appears when `isRefining` is true, allowing the user to disable the mode. Refine mode provides higher precision measurements for accurate target designation.


