---
id: cmd.RotaryPlatform.RotateElevationRelative
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateElevationRelative

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -90, <= 90 |
| 2 | speed | double | >= 0, <= 1 |
| 3 | direction | [[ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



