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

Reset laser rangefinder alignment offsets to factory defaults



### Related Commands

- [[proto/cmd.Lrf_calib.SetOffsets]]



### Implementation Notes

Used during LRF calibration/alignment procedures



