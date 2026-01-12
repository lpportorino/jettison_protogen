# JonGuiDataSystem (ser.JonGuiDataSystem)

**Source:** `jon_shared_data_system.proto`

## Description

State/data message.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| cpu_temperature | double | 1 | - | >= -273.15, <= 150 |
| gpu_temperature | double | 2 | - | >= -273.15, <= 150 |
| gpu_load | double | 3 | - | >= 0, <= 100 |
| cpu_load | double | 4 | - | >= 0, <= 100 |
| power_consumption | double | 5 | - | >= 0, <= 1000 |
| loc | JonGuiDataSystemLocalizations | 6 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| cur_video_rec_dir_year | int32 | 7 | - | >= 0 |
| cur_video_rec_dir_month | int32 | 8 | - | >= 0 |
| cur_video_rec_dir_day | int32 | 9 | - | >= 0 |
| cur_video_rec_dir_hour | int32 | 10 | - | >= 0 |
| cur_video_rec_dir_minute | int32 | 11 | - | >= 0 |
| cur_video_rec_dir_second | int32 | 12 | - | >= 0 |
| rec_enabled | bool | 13 | - | - |
| important_rec_enabled | bool | 14 | - | - |
| low_disk_space | bool | 15 | - | - |
| no_disk_space | bool | 16 | - | >= 0, <= 100 |
| disk_space | int32 | 17 | - | >= 0, <= 100 |
| tracking | bool | 18 | - | - |
| vampire_mode | bool | 19 | - | - |
| stabilization_mode | bool | 20 | - | - |
| geodesic_mode | bool | 21 | - | - |
| cv_dumping | bool | 22 | - | - |
| recognition_mode | bool | 23 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| accumulator_state | JonGuiDataAccumulatorStateIdx | 24 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| ext_bat_capacity | int32 | 25 | - | >= 0, <= 100 |
| ext_bat_status | JonGuiDataExtBatStatus | 26 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_system.proto` for complete context
