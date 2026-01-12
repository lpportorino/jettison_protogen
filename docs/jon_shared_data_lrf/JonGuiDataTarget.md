# JonGuiDataTarget (ser.JonGuiDataTarget)

**Source:** `jon_shared_data_lrf.proto`

## Description

Target location and measurement data.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| timestamp | int64 | 1 | - | >= -180, <= 180 |
| target_longitude | double | 2 | - | >= -180, <= 180 |
| target_latitude | double | 3 | - | >= -90, <= 90 |
| target_altitude | double | 4 | - | >= -180, <= 180 |
| observer_longitude | double | 5 | - | >= -180, <= 180 |
| observer_latitude | double | 6 | - | >= -90, <= 90 |
| observer_altitude | double | 7 | - | >= 0, < 360 |
| observer_azimuth | double | 8 | - | >= 0, < 360 |
| observer_elevation | double | 9 | - | >= -90, <= 90 |
| observer_bank | double | 10 | - | >= -180, < 180 |
| distance_2d | double | 11 | - | >= 0, <= 500000 |
| distance_3b | double | 12 | - | >= 0, <= 500000 |
| observer_fix_type | JonGuiDataGpsFixType | 13 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| session_id | int32 | 14 | - | - |
| target_id | int32 | 15 | - | - |
| target_color | RgbColor | 16 | - | - |
| type | uint32 | 17 | - | - |
| uuid_part1 | int32 | 18 | UUID as four fixed32 values (128 bits total) | - |
| uuid_part2 | int32 | 19 | - | - |
| uuid_part3 | int32 | 20 | - | - |
| uuid_part4 | int32 | 21 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_lrf.proto` for complete context
