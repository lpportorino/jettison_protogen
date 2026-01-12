---
id: cmd.Lrf_calib.ShiftOffsetsBy
proto: jon_shared_cmd_lrf_align.proto
package: cmd.Lrf_calib
type: message
---

# ShiftOffsetsBy

**Source:** `jon_shared_cmd_lrf_align.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.Lrf_calib.SetOffsets]]
- [[proto/proto/cmd.Lrf_calib.SaveOffsets]]
- [[proto/proto/cmd.Lrf_calib.ResetOffsets]]





## Field Notes


### x (#1)


#### Metadata

- **Semantic Type:** :raw
- **Unit:** pixels
- **Display Format:** `{value}px`


### y (#2)


#### Metadata

- **Semantic Type:** :raw
- **Unit:** pixels
- **Display Format:** `{value}px`



