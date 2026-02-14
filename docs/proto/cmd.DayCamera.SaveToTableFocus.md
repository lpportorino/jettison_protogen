---
id: cmd.DayCamera.SaveToTableFocus
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SaveToTableFocus

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Saves the current camera focus position to a lookup table for quick recall. This parameterless trigger command enables the camera to store and later retrieve predefined focus positions, commonly used via a save-focus action button in the day camera controls.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout (ms):** 2000

<!-- NEEDS_REVIEW: Category changed from :actuator to :settings since this persists configuration. Feedback changed to :pending-timeout based on frontend implementation. -->

### Purpose

Saves current focus position to zoom-indexed lookup table for automatic recall. Enables parafocal operation where focus is automatically restored when returning to a previously-saved zoom level.


### Related State

- [[proto/ser.JonGuiDataCameraDay]] - `focusPos` reflects current position being saved


### Related Commands

- [[proto/cmd.DayCamera.Focus]] - Parent composite command containing this operation
- [[proto/cmd.DayCamera.ResetFocus]] - Complementary command to restore saved focus position



### Implementation Notes

Empty message - trigger only. The `jon-day-focus-mover` component exposes this as a "Save" action button alongside a "Reset" button. Both buttons share pending-timeout feedback (2000ms) with time-based state change detection.



