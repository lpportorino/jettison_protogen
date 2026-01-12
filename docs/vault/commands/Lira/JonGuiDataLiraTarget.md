---
id: cmd.Lira.JonGuiDataLiraTarget
proto: jon_shared_cmd_lira.proto
package: cmd.Lira
type: message
---

# JonGuiDataLiraTarget

**Source:** `jon_shared_cmd_lira.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | timestamp | int64 | >= 0 |
| 2 | target_longitude | double | >= -180, <= 180 |
| 3 | target_latitude | double | >= -90, <= 90 |
| 4 | target_altitude | double | >= -430, <= 100000 |
| 5 | target_azimuth | double | >= 0, < 360 |
| 6 | target_elevation | double | >= -90, <= 90 |
| 7 | distance | double | >= 0 |
| 8 | uuid_part1 | int32 | - |
| 9 | uuid_part2 | int32 | - |
| 10 | uuid_part3 | int32 | - |
| 11 | uuid_part4 | int32 | - |



