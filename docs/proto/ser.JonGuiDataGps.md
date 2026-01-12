---
id: ser.JonGuiDataGps
proto: jon_shared_data_gps.proto
package: ser
type: message
---

# JonGuiDataGps

**Source:** `jon_shared_data_gps.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | longitude | double | >= -180, <= 180 |
| 2 | latitude | double | >= -90, <= 90 |
| 3 | altitude | double | >= -430, <= 100000 |
| 4 | manual_longitude | double | >= -180, <= 180 |
| 5 | manual_latitude | double | >= -90, <= 90 |
| 6 | manual_altitude | double | >= -430, <= 100000 |
| 7 | fix_type | [[proto/ser.JonGuiDataGpsFixType]] | defined enum value only, not in: 0 |
| 8 | use_manual | bool | - |
| 9 | timestamp | int64 | - |
| 10 | is_started | bool | - |




