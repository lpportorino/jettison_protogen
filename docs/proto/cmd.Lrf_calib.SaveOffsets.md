---
id: cmd.Lrf_calib.SaveOffsets
proto: jon_shared_cmd_lrf_align.proto
package: cmd.Lrf_calib
type: message
---

# SaveOffsets

**Source:** `jon_shared_cmd_lrf_align.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.Lrf_calib.SetOffsets]]
- [[proto/proto/cmd.Lrf_calib.ResetOffsets]]


### Preconditions

- LRF must be calibrated


### Implementation Notes

Part of LRF alignment calibration workflow



