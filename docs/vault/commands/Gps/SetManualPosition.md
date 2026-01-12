---
id: cmd.Gps.SetManualPosition
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# SetManualPosition

**Source:** `jon_shared_cmd_gps.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | latitude | double | >= -90, <= 90 |
| 2 | longitude | double | >= -180, < 180 |
| 3 | altitude | double | >= -430, <= 100000 |



