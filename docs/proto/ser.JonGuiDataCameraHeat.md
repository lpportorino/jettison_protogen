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
| 12 | horizontal_fov_degrees | double | >= 0, < 360 |
| 13 | vertical_fov_degrees | double | >= 0, < 360 |
| 14 | is_started | bool | - |
| 15 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |
| 16 | capture_monotonic_us | uint64 | - |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Feedback:** :poll-confirm


### Purpose

Thermal camera status, settings, and operational data



### Related Commands

- [[proto/cmd.HeatCamera.Start]]
- [[proto/cmd.HeatCamera.Stop]]
- [[proto/cmd.HeatCamera.SetAGC]]
- [[proto/cmd.HeatCamera.SetFilters]]
- [[proto/cmd.HeatCamera.SetDDELevel]]
- [[proto/cmd.HeatCamera.SetDigitalZoomLevel]]



### Implementation Notes

Provides real-time thermal camera state including AGC mode, filter, zoom levels, and DDE settings



## Field Notes


### zoom_pos (#1)

Normalized value (0.0 to 1.0)


### agc_mode (#2)

See related enum for valid values


### filter (#3)

See related enum for valid values


### auto_focus (#4)

Auto-focus enabled state


### zoom_table_pos (#5)

Current zoom table position


### zoom_table_pos_max (#6)

Maximum zoom table position


### dde_level (#7)

DDE (Dynamic Detail Enhancement) level


### fx_mode (#9)

See related enum for valid values


### digital_zoom_level (#10)

Digital zoom multiplier


### clahe_level (#11)

Normalized value (0.0 to 1.0)


### horizontal_fov_degrees (#12)

Horizontal field of view in degrees


### vertical_fov_degrees (#13)

Vertical field of view in degrees


### is_started (#14)

Heat camera started state


#### Metadata

- **Semantic Type:** :toggle-state


### meteo (#15)

Local environmental sensor data from the thermal camera, providing temperature, humidity, and pressure readings for system diagnostics and thermal management.


### capture_monotonic_us (#16)

CLOCK_MONOTONIC timestamp in microseconds, stamped when state is pushed to SHM in the sync timer. Approximates when the data was last captured.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** us



