---
id: cmd.Lrf_calib.SetOffsets
proto: jon_shared_cmd_lrf_align.proto
package: cmd.Lrf_calib
type: message
---

# SetOffsets

**Source:** `jon_shared_cmd_lrf_align.proto`

## Description

Sets the X and Y laser rangefinder calibration offsets for either day or thermal camera channels, updating the crosshair alignment at the current zoom level.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | x | int32 | >= -1920, <= 1920 |
| 2 | y | int32 | >= -1080, <= 1080 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :directional-mover
- **Feedback:** :pending-timeout


### Purpose

Sets the absolute X and Y pixel offsets for aligning the LRF crosshair with the camera view. The command is routed to either the day or thermal camera channel via the parent `Lrf_calib.Offsets` message. Offsets are applied to the crosshair overlay in the video stream, positioning the crosshair at (center + offset) coordinates.

### Related State

- [[proto/ser.JonGuiDataRecOsd]] - Exposes `dayCrosshairOffsetHorizontal`, `dayCrosshairOffsetVertical`, `heatCrosshairOffsetHorizontal`, `heatCrosshairOffsetVertical` fields reflecting current offsets

### Related Commands

- [[proto/cmd.Lrf_calib.ShiftOffsetsBy]]
- [[proto/cmd.Lrf_calib.SaveOffsets]]
- [[proto/cmd.Lrf_calib.ResetOffsets]]



### Preconditions

- Camera (day or heat) must be started
- LRF module must be initialized

### Implementation Notes

Separate calibration offsets are maintained for day and heat channels. The offset values are stored per zoom level to account for optical parallax at different magnifications. The frontend uses `lrfCalibSetDayOffsets(x, y)` or `lrfCalibSetHeatOffsets(x, y)` to send this command. Changes are immediately reflected in the `recOsd` state and the crosshair overlay updates accordingly.



## Field Notes


### x (#1)

Horizontal pixel offset from frame center. Positive values shift the crosshair right, negative values shift left. The constraint range [-1920, 1920] covers the full width of the day camera frame (1920x1080), while heat camera frames are 900x720.

#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Unit:** px
- **Display Format:** `{value}px`


### y (#2)

Vertical pixel offset from frame center. Positive values shift the crosshair down, negative values shift up. The constraint range [-1080, 1080] covers the full height of the day camera frame.

#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Unit:** px
- **Display Format:** `{value}px`



