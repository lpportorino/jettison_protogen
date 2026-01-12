---
id: cmd.Lrf.Start
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# Start

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Starts the laser rangefinder measurement operation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Initiates laser rangefinder operation to begin distance measurements.


### Related State

- [[ser.JonGuiDataLrf]]


### Related Commands

- [[cmd.Lrf.Stop]]



### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The UI should provide a toggle control that reflects the current running state of the rangefinder.



