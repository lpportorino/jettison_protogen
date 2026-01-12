---
id: ser.JonGuiDataCameraDay
proto: jon_shared_data_camera_day.proto
package: ser
type: message
---

# JonGuiDataCameraDay

**Source:** `jon_shared_data_camera_day.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | focus_pos | double | >= 0, <= 1 |
| 2 | zoom_pos | double | >= 0, <= 1 |
| 3 | iris_pos | double | >= 0, <= 1 |
| 4 | infrared_filter | bool | - |
| 5 | zoom_table_pos | int32 | >= 0 |
| 6 | zoom_table_pos_max | int32 | >= 0 |
| 7 | fx_mode | [[proto/ser.JonGuiDataFxModeDay]] | defined enum value only |
| 8 | auto_focus | bool | - |
| 9 | auto_iris | bool | - |
| 15 | auto_gain | bool | - |
| 10 | digital_zoom_level | double | >= 1 |
| 11 | clahe_level | double | >= 0, <= 1 |
| 12 | horizontal_fov_degrees | double | > 0, < 360 |
| 13 | vertical_fov_degrees | double | > 0, < 360 |
| 14 | is_started | bool | - |




