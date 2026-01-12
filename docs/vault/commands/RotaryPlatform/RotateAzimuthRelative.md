---
id: cmd.RotaryPlatform.RotateAzimuthRelative
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuthRelative

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -180, < 180 |
| 2 | speed | double | >= 0, <= 1 |
| 3 | direction | [[ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |




