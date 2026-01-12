# JonGuiDataRotary (ser.JonGuiDataRotary)

**Source:** `jon_shared_data_rotary.proto`

## Description

State data for Rotary subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| azimuth | double | 1 | - | >= 0, < 360 |
| azimuth_speed | double | 2 | - | >= -1.0, <= 1.0 |
| elevation | double | 3 | - | >= -90, <= 90 |
| elevation_speed | double | 4 | - | >= -1.0, <= 1.0 |
| platform_azimuth | double | 5 | - | >= 0, < 360 |
| platform_elevation | double | 6 | - | >= -90, <= 90 |
| platform_bank | double | 7 | - | >= -180, < 180 |
| is_moving | bool | 8 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| mode | JonGuiDataRotaryMode | 9 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| is_scanning | bool | 10 | - | - |
| is_scanning_paused | bool | 11 | - | - |
| use_rotary_as_compass | bool | 12 | - | >= 0 |
| scan_target | int32 | 13 | - | >= 0 |
| scan_target_max | int32 | 14 | - | >= 0 |
| sun_azimuth | double | 15 | - | >= 0, < 360 |
| sun_elevation | double | 16 | - | >= 0, < 360 |
| current_scan_node | ScanNode | 17 | - | - |
| is_started | bool | 18 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_rotary.proto` for complete context
