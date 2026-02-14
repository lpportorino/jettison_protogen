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

- [[proto/ser.JonGuiDataRecOsd]]


### Related Commands

- [[proto/cmd.Lrf_calib.SetOffsets]]
- [[proto/cmd.Lrf_calib.SaveOffsets]]
- [[proto/cmd.Lrf_calib.ResetOffsets]]


### Implementation Notes

The frontend implements this as a directional stepper with four buttons per axis: fine adjustment (+/-1 pixel) and coarse adjustment (+/-10 pixels). The command is wrapped inside `cmd.Lrf_calib.Offsets.shift` for either the `day` or `heat` channel in `cmd.Lrf_calib.Root`. After sending, the UI monitors `dayCrosshairOffsetHorizontal`/`dayCrosshairOffsetVertical` or `heatCrosshairOffsetHorizontal`/`heatCrosshairOffsetVertical` in `ser.JonGuiDataRecOsd` to confirm the offset changed, with a 2-second pending timeout.





## Field Notes


### x (#1)

Horizontal pixel delta to shift the crosshair offset. Positive values move the crosshair right, negative values move it left.


#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Unit:** pixels
- **Display Format:** `{value}px`


### y (#2)

Vertical pixel delta to shift the crosshair offset. Positive values move the crosshair down, negative values move it up.


#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Unit:** pixels
- **Display Format:** `{value}px`



