---
id: cmd.Lrf_calib.ResetOffsets
proto: jon_shared_cmd_lrf_align.proto
package: cmd.Lrf_calib
type: message
---

# ResetOffsets

**Source:** `jon_shared_cmd_lrf_align.proto`

## Description

Resets laser rangefinder calibration offsets for either the day or thermal camera channel to their saved defaults, restoring the crosshair alignment to the previously stored calibration values.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Reverts the laser rangefinder crosshair alignment offsets for the specified camera channel (day or thermal) to their last saved values stored in Redis, discarding any unsaved adjustments made during the current calibration session.


### Related State

- [[proto/ser.JonGuiDataRecOsd]]


### Related Commands

- [[proto/cmd.Lrf_calib.SetOffsets]]
- [[proto/cmd.Lrf_calib.SaveOffsets]]
- [[proto/cmd.Lrf_calib.ShiftOffsetsBy]]


### Implementation Notes

Used during LRF calibration/alignment procedures. Triggered by the "Reset" button in the Day/Heat Crosshair Position panels (`jonDayCrosshairMover`, `jonHeatCrosshairMover` Lit components). The reset operation restores offsets to the values previously persisted via `SaveOffsets`, allowing operators to undo calibration changes without restarting the system.



