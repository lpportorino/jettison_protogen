# JonGuiDataCameraHeat (ser.JonGuiDataCameraHeat)

**Source:** `jon_shared_data_camera_heat.proto`

## Description

State data for CameraHeat subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| zoom_pos | double | 1 | - | >= 0.0, <= 1.0 |
| agc_mode | JonGuiDataVideoChannelHeatAGCModes | 2 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| filter | JonGuiDataVideoChannelHeatFilters | 3 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| auto_focus | bool | 4 | - | >= 0 |
| zoom_table_pos | int32 | 5 | - | >= 0 |
| zoom_table_pos_max | int32 | 6 | - | >= 0 |
| dde_level | int32 | 7 | - | >= 0, <= 512 |
| dde_enabled | bool | 8 | - | must be defined enum value |
| fx_mode | JonGuiDataFxModeHeat | 9 | - | must be defined enum value |
| digital_zoom_level | double | 10 | - | >= 1.0 |
| clahe_level | double | 11 | - | >= 0.0, <= 1.0 |
| horizontal_fov_degrees | double | 12 | - | > 0.0, < 360.0 |
| vertical_fov_degrees | double | 13 | - | > 0.0, < 360.0 |
| is_started | bool | 14 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_camera_heat.proto` for complete context
