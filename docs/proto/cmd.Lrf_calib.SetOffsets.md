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

Sets LRF calibration offsets for alignment with camera view



### Related Commands

- [[proto/cmd.Lrf_calib.ShiftOffsetsBy]]
- [[proto/cmd.Lrf_calib.SaveOffsets]]
- [[proto/cmd.Lrf_calib.ResetOffsets]]



### Implementation Notes

Separate calibration offsets for day and heat channels



## Field Notes


### x (#1)

Temperature in degrees Celsius


#### Metadata

- **Semantic Type:** :raw
- **Unit:** px
- **Display Format:** `{value}px`


### y (#2)

Temperature in degrees Celsius


#### Metadata

- **Semantic Type:** :raw
- **Unit:** px
- **Display Format:** `{value}px`



