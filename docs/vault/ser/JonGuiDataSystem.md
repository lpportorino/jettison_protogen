---
id: ser.JonGuiDataSystem
proto: jon_shared_data_system.proto
package: ser
type: message
---

# JonGuiDataSystem

**Source:** `jon_shared_data_system.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | cpu_temperature | double | >= -273.15, <= 150 |
| 2 | gpu_temperature | double | >= -273.15, <= 150 |
| 3 | gpu_load | double | >= 0, <= 100 |
| 4 | cpu_load | double | >= 0, <= 100 |
| 5 | power_consumption | double | >= 0, <= 1000 |
| 6 | loc | [[ser.JonGuiDataSystemLocalizations]] | defined enum value only, not in: 0 |
| 7 | cur_video_rec_dir_year | int32 | >= 0 |
| 8 | cur_video_rec_dir_month | int32 | >= 0 |
| 9 | cur_video_rec_dir_day | int32 | >= 0 |
| 10 | cur_video_rec_dir_hour | int32 | >= 0 |
| 11 | cur_video_rec_dir_minute | int32 | >= 0 |
| 12 | cur_video_rec_dir_second | int32 | >= 0 |
| 13 | rec_enabled | bool | - |
| 14 | important_rec_enabled | bool | - |
| 15 | low_disk_space | bool | - |
| 16 | no_disk_space | bool | - |
| 17 | disk_space | int32 | >= 0, <= 100 |
| 18 | tracking | bool | - |
| 19 | vampire_mode | bool | - |
| 20 | stabilization_mode | bool | - |
| 21 | geodesic_mode | bool | - |
| 22 | cv_dumping | bool | - |
| 23 | recognition_mode | bool | - |
| 24 | accumulator_state | [[ser.JonGuiDataAccumulatorStateIdx]] | defined enum value only, not in: 0 |
| 25 | ext_bat_capacity | int32 | >= 0, <= 100 |
| 26 | ext_bat_status | [[ser.JonGuiDataExtBatStatus]] | - |



