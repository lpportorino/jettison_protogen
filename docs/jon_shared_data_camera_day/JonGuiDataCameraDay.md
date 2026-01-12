# JonGuiDataCameraDay (ser.JonGuiDataCameraDay)

**Source:** `jon_shared_data_camera_day.proto`

## Description

State data for CameraDay subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| focus_pos | double | 1 | - | >= 0.0, <= 1.0 |
| zoom_pos | double | 2 | - | >= 0.0, <= 1.0 |
| iris_pos | double | 3 | - | >= 0.0, <= 1.0 |
| infrared_filter | bool | 4 | - | >= 0 |
| zoom_table_pos | int32 | 5 | - | >= 0 |
| zoom_table_pos_max | int32 | 6 | - | >= 0 |
| fx_mode | JonGuiDataFxModeDay | 7 | - | must be defined enum value |
| auto_focus | bool | 8 | - | - |
| auto_iris | bool | 9 | - | - |
| auto_gain | bool | 15 | - | >= 1.0 |
| digital_zoom_level | double | 10 | - | >= 1.0 |
| clahe_level | double | 11 | - | >= 0.0, <= 1.0 |
| horizontal_fov_degrees | double | 12 | - | > 0.0, < 360.0 |
| vertical_fov_degrees | double | 13 | - | > 0.0, < 360.0 |
| is_started | bool | 14 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_camera_day.proto` for complete context
