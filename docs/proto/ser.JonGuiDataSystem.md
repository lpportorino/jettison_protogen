---
id: ser.JonGuiDataSystem
proto: jon_shared_data_system.proto
package: ser
type: message
---

# JonGuiDataSystem

**Source:** `jon_shared_data_system.proto`

## Description

Captures comprehensive device telemetry including hardware metrics (CPU/GPU temperature and load), recording state with timestamped directories, storage status with warning indicators, operational modes (tracking, stabilization, recognition, geodesic, vampire, CV dumping), and battery status, enabling real-time monitoring of system health and operational state in the frontend UI.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | cpu_temperature | double | >= -273.15, <= 150 |
| 2 | gpu_temperature | double | >= -273.15, <= 150 |
| 3 | gpu_load | double | >= 0, <= 100 |
| 4 | cpu_load | double | >= 0, <= 100 |
| 5 | power_consumption | double | >= 0, <= 1000 |
| 6 | loc | [[proto/ser.JonGuiDataSystemLocalizations]] | defined enum value only, not in: 0 |
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
| 24 | accumulator_state | [[proto/ser.JonGuiDataAccumulatorStateIdx]] | defined enum value only, not in: 0 |
| 25 | ext_bat_capacity | int32 | >= 0, <= 100 |
| 26 | ext_bat_status | [[proto/ser.JonGuiDataExtBatStatus]] | - |



## Interaction

- **Category:** :status


### Purpose

System health, resource usage, and operational mode status



### Related Commands

- [[proto/proto/cmd.System.DisableGeodesicMode]]
- [[proto/proto/cmd.System.EnableGeodesicMode]]
- [[proto/proto/cmd.System.SaveFactoryDefaults]]



### Implementation Notes

Comprehensive system status including CPU/GPU metrics, recording state, operational modes (tracking, vampire, stabilization, geodesic, CV dumping, recognition), and battery status.



## Field Notes


### cpu_temperature (#1)


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1
- **Display Format:** `{value}°C`


### gpu_temperature (#2)


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1
- **Display Format:** `{value}°C`


### gpu_load (#3)


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value}%`


### cpu_load (#4)


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value}%`


### power_consumption (#5)


#### Metadata

- **Semantic Type:** :power
- **Unit:** W
- **Precision:** 1
- **Display Format:** `{value}W`


### disk_space (#17)


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value}%`


### tracking (#18)


#### Metadata

- **Semantic Type:** :raw


### vampire_mode (#19)


#### Metadata

- **Semantic Type:** :raw


### stabilization_mode (#20)


#### Metadata

- **Semantic Type:** :raw


### geodesic_mode (#21)


#### Metadata

- **Semantic Type:** :raw


### cv_dumping (#22)


#### Metadata

- **Semantic Type:** :raw


### recognition_mode (#23)


#### Metadata

- **Semantic Type:** :raw


### ext_bat_capacity (#25)


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value}%`



