---
id: cmd.Lrf_calib.Offsets
proto: jon_shared_cmd_lrf_align.proto
package: cmd.Lrf_calib
type: message
---

# Offsets

**Source:** `jon_shared_cmd_lrf_align.proto`

## Description

A union message that contains one of four LRF calibration offset operations (set, save, reset, or shift) for adjusting laser rangefinder crosshair alignment on either the day or thermal imaging camera.

The Offsets message is wrapped by `cmd.Lrf_calib.Root` which specifies whether the operation applies to the day camera channel (`day`) or thermal camera channel (`heat`). Each camera has independent calibration offsets stored per zoom level, allowing the LRF crosshair to remain aligned as the user zooms in or out.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | set | [[proto/cmd.Lrf_calib.SetOffsets]] | - |
| 2 | save | [[proto/cmd.Lrf_calib.SaveOffsets]] | - |
| 3 | reset | [[proto/cmd.Lrf_calib.ResetOffsets]] | - |
| 4 | shift | [[proto/cmd.Lrf_calib.ShiftOffsetsBy]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4




## Interaction

- **Category:** :settings
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Set calibration offsets for laser rangefinder alignment


### Related State

- [[proto/ser.JonGuiDataLrf]]
- `rec_osd.day_crosshair_offset_horizontal` - Current day camera X offset (reflected in OSD)
- `rec_osd.day_crosshair_offset_vertical` - Current day camera Y offset (reflected in OSD)
- `rec_osd.heat_crosshair_offset_horizontal` - Current thermal camera X offset
- `rec_osd.heat_crosshair_offset_vertical` - Current thermal camera Y offset


### Related Commands

- [[proto/cmd.Lrf.Measure]] - Take a range measurement (requires aligned crosshair)
- [[proto/cmd.Lrf.ScanOn]] - Start continuous ranging (requires aligned crosshair)




### Preconditions

- Camera must be streaming video for visual feedback
- Zoom level should be set before calibrating (offsets are stored per zoom level)


### Implementation Notes

Used in the LRF calibration workflow accessible from the Alignment Palette in the frontend UI. The typical workflow is:
1. Use `shift` commands for incremental adjustment (arrow buttons in UI)
2. Visually verify crosshair alignment with laser spot on target
3. Use `save` to persist offsets to Redis for the current zoom level
4. Use `reset` to revert to last saved values if adjustments are unsatisfactory

Offsets are persisted to Redis and survive system restarts. The UI provides "Day Crosshair Position" and "Heat Crosshair Position" panels with directional buttons for 1px and 10px increments.



## Field Notes


### set (#1)

Sets absolute X/Y pixel offsets for the crosshair position. Used when exact offset values are known. Constraints: X in [-1920, 1920], Y in [-1080, 1080] pixels. See [[proto/cmd.Lrf_calib.SetOffsets]] for field details.


### save (#2)

Persists current in-memory offsets to Redis storage. Offsets are stored per zoom level for each camera channel. Call after visual verification that the crosshair is correctly aligned with the LRF laser spot. See [[proto/cmd.Lrf_calib.SaveOffsets]].


### reset (#3)

Reverts in-memory offsets to the last saved values from Redis. Use to discard in-progress adjustments without affecting stored calibration. See [[proto/cmd.Lrf_calib.ResetOffsets]].


### shift (#4)

Incrementally adjusts offsets by delta X/Y values. Most common operation in the UI, triggered by directional buttons. Typical increments are 1px (fine) or 10px (coarse). See [[proto/cmd.Lrf_calib.ShiftOffsetsBy]] for field details.



