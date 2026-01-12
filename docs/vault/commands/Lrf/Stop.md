---
id: cmd.Lrf.Stop
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# Stop

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Stops the laser rangefinder measurement operation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Terminates laser rangefinder operation to stop distance measurements.

### Related State

- [[ser.JonGuiDataLrf]]

### Related Commands

- [[cmd.Lrf.Start]]

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The UI should provide a toggle control that reflects the current running state of the rangefinder.



