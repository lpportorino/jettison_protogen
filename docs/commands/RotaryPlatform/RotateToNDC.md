---
id: cmd.RotaryPlatform.RotateToNDC
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateToNDC

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | channel | [[ser.JonGuiDataVideoChannel]] | defined enum value only, not in: 0 |
| 2 | x | double | >= -1, <= 1 |
| 3 | y | double | >= -1, <= 1 |
| 4 | frame_time | uint64 | - |
| 5 | state_time | uint64 | - |




