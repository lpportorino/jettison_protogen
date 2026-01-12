---
id: ser.JonGuiDataActualSpaceTime
proto: jon_shared_data_actual_space_time.proto
package: ser
type: message
---

# JonGuiDataActualSpaceTime

**Source:** `jon_shared_data_actual_space_time.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | azimuth | double | >= 0, < 360 |
| 2 | elevation | double | >= -90, <= 90 |
| 3 | bank | double | >= -180, < 180 |
| 4 | latitude | double | >= -90, <= 90 |
| 5 | longitude | double | >= -180, < 180 |
| 6 | altitude | double | >= -430, <= 100000 |
| 7 | timestamp | int64 | >= 0 |




