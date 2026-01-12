---
id: cmd.RotaryPlatform.RotateToGPS
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateToGPS

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | latitude | double | >= -90, <= 90 |
| 2 | longitude | double | >= -180, < 180 |
| 3 | altitude | double | >= -430, <= 100000 |




