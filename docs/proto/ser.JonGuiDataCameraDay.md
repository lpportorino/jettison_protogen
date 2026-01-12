---
id: ser.JonGuiDataCameraDay
proto: jon_shared_data_camera_day.proto
package: ser
type: message
---

# JonGuiDataCameraDay

**Source:** `jon_shared_data_camera_day.proto`

## Description

Captures the complete operational state of the day camera, including normalized control positions (focus, zoom, iris), automatic control modes (auto-focus, auto-iris, auto-gain), field of view angles, and image processing parameters like CLAHE level and FX mode presets.

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



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator


### Purpose

Day camera state including zoom, focus, iris, and image processing settings



### Related Commands

- [[proto/proto/proto/proto/proto/cmd.DayCamera.SetIris]]
- [[proto/proto/proto/proto/proto/cmd.DayCamera.SetAutoIris]]
- [[proto/proto/proto/proto/proto/cmd.DayCamera.SetFxMode]]





## Field Notes


### focus_pos (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3


### zoom_pos (#2)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3


### iris_pos (#3)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`


### infrared_filter (#4)


#### Metadata

- **Semantic Type:** :raw


### zoom_table_pos (#5)


#### Metadata

- **Semantic Type:** :raw


### zoom_table_pos_max (#6)


#### Metadata

- **Semantic Type:** :raw


### fx_mode (#7)


#### Metadata

- **Semantic Type:** :enum-label


### auto_focus (#8)


#### Metadata

- **Semantic Type:** :raw


### auto_iris (#9)


#### Metadata

- **Semantic Type:** :count


### digital_zoom_level (#10)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 2


### clahe_level (#11)


#### Metadata

- **Semantic Type:** :raw



