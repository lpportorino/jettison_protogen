---
id: ser.JonGuiDataCameraHeat
proto: jon_shared_data_camera_heat.proto
package: ser
type: message
---

# JonGuiDataCameraHeat

**Source:** `jon_shared_data_camera_heat.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | zoom_pos | double | >= 0, <= 1 |
| 2 | agc_mode | [[ser.JonGuiDataVideoChannelHeatAGCModes]] | defined enum value only, not in: 0 |
| 3 | filter | [[ser.JonGuiDataVideoChannelHeatFilters]] | defined enum value only, not in: 0 |
| 4 | auto_focus | bool | - |
| 5 | zoom_table_pos | int32 | >= 0 |
| 6 | zoom_table_pos_max | int32 | >= 0 |
| 7 | dde_level | int32 | >= 0, <= 512 |
| 8 | dde_enabled | bool | - |
| 9 | fx_mode | [[ser.JonGuiDataFxModeHeat]] | defined enum value only |
| 10 | digital_zoom_level | double | >= 1 |
| 11 | clahe_level | double | >= 0, <= 1 |
| 12 | horizontal_fov_degrees | double | > 0, < 360 |
| 13 | vertical_fov_degrees | double | > 0, < 360 |
| 14 | is_started | bool | - |




