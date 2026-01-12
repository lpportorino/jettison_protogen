---
id: cmd.RotaryPlatform.RotateAzimuthRelativeSet
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuthRelativeSet

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -180, < 180 |
| 2 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |




