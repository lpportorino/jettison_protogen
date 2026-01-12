---
id: ser.JonGuiDataRotary
proto: jon_shared_data_rotary.proto
package: ser
type: message
---

# JonGuiDataRotary

**Source:** `jon_shared_data_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | azimuth | double | >= 0, < 360 |
| 2 | azimuth_speed | double | >= -1, <= 1 |
| 3 | elevation | double | >= -90, <= 90 |
| 4 | elevation_speed | double | >= -1, <= 1 |
| 5 | platform_azimuth | double | >= 0, < 360 |
| 6 | platform_elevation | double | >= -90, <= 90 |
| 7 | platform_bank | double | >= -180, < 180 |
| 8 | is_moving | bool | - |
| 9 | mode | [[ser.JonGuiDataRotaryMode]] | defined enum value only, not in: 0 |
| 10 | is_scanning | bool | - |
| 11 | is_scanning_paused | bool | - |
| 12 | use_rotary_as_compass | bool | - |
| 13 | scan_target | int32 | >= 0 |
| 14 | scan_target_max | int32 | >= 0 |
| 15 | sun_azimuth | double | >= 0, < 360 |
| 16 | sun_elevation | double | >= 0, < 360 |
| 17 | current_scan_node | [[ser.ScanNode]] | required |
| 18 | is_started | bool | - |




