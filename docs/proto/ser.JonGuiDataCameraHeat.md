---
id: ser.JonGuiDataCameraHeat
proto: jon_shared_data_camera_heat.proto
package: ser
type: message
---

# JonGuiDataCameraHeat

**Source:** `jon_shared_data_camera_heat.proto`

## Description

Represents the complete operational and configuration state of the thermal/infrared camera system, including optical parameters (zoom position, field-of-view, focus mode), image processing settings (AGC mode, filter selection, CLAHE enhancement, DDE dynamics enhancement), and operational status.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | zoom_pos | double | >= 0, <= 1 |
| 2 | agc_mode | [[proto/ser.JonGuiDataVideoChannelHeatAGCModes]] | defined enum value only, not in: 0 |
| 3 | filter | [[proto/ser.JonGuiDataVideoChannelHeatFilters]] | defined enum value only, not in: 0 |
| 4 | auto_focus | bool | - |
| 5 | zoom_table_pos | int32 | >= 0 |
| 6 | zoom_table_pos_max | int32 | >= 0 |
| 7 | dde_level | int32 | >= 0, <= 512 |
| 8 | dde_enabled | bool | - |
| 9 | fx_mode | [[proto/ser.JonGuiDataFxModeHeat]] | defined enum value only |
| 10 | digital_zoom_level | double | >= 1 |
| 11 | clahe_level | double | >= 0, <= 1 |
| 12 | horizontal_fov_degrees | double | > 0, < 360 |
| 13 | vertical_fov_degrees | double | > 0, < 360 |
| 14 | is_started | bool | - |
| 15 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Feedback:** :poll-confirm


### Purpose

Thermal camera status, settings, and operational data



### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.Start]]
- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.Stop]]
- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.SetAgc]]
- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.SetFilter]]
- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.SetDDELevel]]
- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.SetDigitalZoomLevel]]



### Implementation Notes

Provides real-time thermal camera state including AGC mode, filter, zoom levels, and DDE settings


## Field Notes


### meteo (#15)

Local environmental sensor data from the thermal camera, providing temperature, humidity, and pressure readings for system diagnostics and thermal management.


