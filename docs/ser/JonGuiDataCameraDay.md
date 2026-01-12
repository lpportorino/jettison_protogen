---
id: ser.JonGuiDataCameraDay
proto: jon_shared_data_camera_day.proto
package: ser
type: message
---

# JonGuiDataCameraDay

**Source:** `jon_shared_data_camera_day.proto`

## Description

Day camera state including zoom, focus, iris positions and modes. Provides real-time feedback on camera settings, automation states, and field of view calculations.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | focus_pos | double | >= 0, <= 1 |
| 2 | zoom_pos | double | >= 0, <= 1 |
| 3 | iris_pos | double | >= 0, <= 1 |
| 4 | infrared_filter | bool | - |
| 5 | zoom_table_pos | int32 | >= 0 |
| 6 | zoom_table_pos_max | int32 | >= 0 |
| 7 | fx_mode | [[ser.JonGuiDataFxModeDay]] | defined enum value only |
| 8 | auto_focus | bool | - |
| 9 | auto_iris | bool | - |
| 15 | auto_gain | bool | - |
| 10 | digital_zoom_level | double | >= 1 |
| 11 | clahe_level | double | >= 0, <= 1 |
| 12 | horizontal_fov_degrees | double | > 0, < 360 |
| 13 | vertical_fov_degrees | double | > 0, < 360 |
| 14 | is_started | bool | - |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator


### Purpose

Provides real-time state information for the day camera subsystem. UI components should display current positions, automation states, and field of view information to provide visual feedback for camera control commands.



### Related Commands

- [[cmd.DayCamera.SetIris]]
- [[cmd.DayCamera.SetAutoIris]]
- [[cmd.DayCamera.Focus]]
- [[cmd.DayCamera.Zoom]]
- [[cmd.DayCamera.SetInfraRedFilter]]
- [[cmd.DayCamera.SetAutoGain]]
- [[cmd.DayCamera.SetDigitalZoomLevel]]
- [[cmd.DayCamera.SetClaheLevel]]
- [[cmd.DayCamera.SetFxMode]]
- [[cmd.DayCamera.Start]]
- [[cmd.DayCamera.Stop]]





## Field Notes


### focus_pos (#1)

Current focus position (normalized).


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`


### zoom_pos (#2)

Current zoom position (normalized).


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`


### iris_pos (#3)

Current iris/aperture position (normalized).


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`


### infrared_filter (#4)

IR filter state (true = inserted, false = removed).


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;IR Filter: ON/OFF&quot; or icon`


### zoom_table_pos (#5)

Current discrete zoom position index.


#### Metadata

- **Semantic Type:** :count
- **Display Format:** `{zoom_table_pos} / {zoom_table_pos_max}`


### zoom_table_pos_max (#6)

Maximum zoom table position index.


#### Metadata

- **Semantic Type:** :count
- **Display Format:** `Used in position display`


### auto_focus (#8)

Auto-focus enabled state.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;AF: ON/OFF&quot; or toggle indicator`


### auto_iris (#9)

Auto-iris enabled state.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Auto Iris: ON/OFF&quot; or toggle indicator`


### auto_gain (#15)

Auto-gain enabled state.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Auto Gain: ON/OFF&quot; or toggle indicator`


### digital_zoom_level (#10)

Current digital zoom multiplier.


#### Metadata

- **Semantic Type:** :count
- **Unit:** x
- **Precision:** 1
- **Display Format:** `{value}x`


### clahe_level (#11)

CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level.


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`


### horizontal_fov_degrees (#12)

Current horizontal field of view.


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}° HFOV`


### vertical_fov_degrees (#13)

Current vertical field of view.


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}° VFOV`


### is_started (#14)

Camera subsystem running state.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Camera: Started/Stopped&quot; or status indicator`



