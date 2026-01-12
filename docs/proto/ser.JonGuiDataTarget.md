---
id: ser.JonGuiDataTarget
proto: jon_shared_data_lrf.proto
package: ser
type: message
---

# JonGuiDataTarget

**Source:** `jon_shared_data_lrf.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | timestamp | int64 | >= 0 |
| 2 | target_longitude | double | >= -180, <= 180 |
| 3 | target_latitude | double | >= -90, <= 90 |
| 4 | target_altitude | double | - |
| 5 | observer_longitude | double | >= -180, <= 180 |
| 6 | observer_latitude | double | >= -90, <= 90 |
| 7 | observer_altitude | double | - |
| 8 | observer_azimuth | double | >= 0, < 360 |
| 9 | observer_elevation | double | >= -90, <= 90 |
| 10 | observer_bank | double | >= -180, < 180 |
| 11 | distance_2d | double | >= 0, <= 500000 |
| 12 | distance_3b | double | >= 0, <= 500000 |
| 13 | observer_fix_type | [[proto/ser.JonGuiDataGpsFixType]] | defined enum value only, not in: 0 |
| 14 | session_id | int32 | >= 0 |
| 15 | target_id | int32 | >= 0 |
| 16 | target_color | [[proto/ser.RgbColor]] | - |
| 17 | type | uint32 | - |
| 18 | uuid_part1 | int32 | - |
| 19 | uuid_part2 | int32 | - |
| 20 | uuid_part3 | int32 | - |
| 21 | uuid_part4 | int32 | - |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Feedback:** :poll-confirm


### Purpose

Target tracking and designation data





### Implementation Notes

Displays tracked target information, coordinates, and designation status



