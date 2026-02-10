---
id: cmd.Lrf_calib.ShiftOffsetsBy
proto: jon_shared_cmd_lrf_align.proto
package: cmd.Lrf_calib
type: message
---

# ShiftOffsetsBy

**Source:** `jon_shared_cmd_lrf_align.proto`

## Description

Incrementally adjusts the laser rangefinder (LRF) calibration offsets by the specified x and y delta values for either day or thermal camera modes, shifting the crosshair alignment relative to the current calibration state.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | x | int32 | >= -1920, <= 1920 |
| 2 | y | int32 | >= -1080, <= 1080 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Shift LRF calibration offsets by incremental x/y pixels


### Related State

- [[proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/cmd.Lrf_calib.SetOffsets]]
- [[proto/cmd.Lrf_calib.SaveOffsets]]
- [[proto/cmd.Lrf_calib.ResetOffsets]]





## Field Notes


### x (#1)

Temperature in degrees Celsius


#### Metadata

- **Semantic Type:** :raw
- **Unit:** pixels
- **Display Format:** `{value}px`


### y (#2)

Temperature in degrees Celsius


#### Metadata

- **Semantic Type:** :raw
- **Unit:** pixels
- **Display Format:** `{value}px`



