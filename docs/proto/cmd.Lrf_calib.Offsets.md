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


### Purpose

Set calibration offsets for laser rangefinder alignment


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataLrf]]




### Implementation Notes

Used in LRF calibration workflow



