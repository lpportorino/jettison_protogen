---
id: cmd.Lrf_calib.SaveOffsets
proto: jon_shared_cmd_lrf_align.proto
package: cmd.Lrf_calib
type: message
---

# SaveOffsets

**Source:** `jon_shared_cmd_lrf_align.proto`

## Description

Persists the current LRF (Laser Rangefinder) crosshair alignment offsets for either day or thermal camera channels to persistent storage. This is an empty message (no fields) that triggers the save operation when sent within the appropriate channel context (`Lrf_calib.Offsets.save`).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Saves current LRF calibration offsets to persistent storage


### Related State

- [[proto/ser.JonGuiDataRecOsd]] - Exposes `dayCrosshairOffsetHorizontal`, `dayCrosshairOffsetVertical`, `heatCrosshairOffsetHorizontal`, `heatCrosshairOffsetVertical` fields reflecting current offsets


### Related Commands

- [[proto/cmd.Lrf_calib.SetOffsets]] - Sets absolute X/Y offsets
- [[proto/cmd.Lrf_calib.ShiftOffsetsBy]] - Adjusts offsets by delta values
- [[proto/cmd.Lrf_calib.ResetOffsets]] - Restores offsets to saved defaults


### Preconditions

- Crosshair offsets must have been modified (via SetOffsets or ShiftOffsetsBy)


### Implementation Notes

Part of LRF alignment calibration workflow. The frontend exposes this via "Save" buttons in the Day Crosshair Mover (`jon-day-crosshair-mover`) and Heat Crosshair Mover (`jon-heat-crosshair-mover`) components. After modifying offsets using the directional controls, the user clicks "Save" to persist the calibration. The "Reset" button sends `ResetOffsets` to revert to the last saved values.



