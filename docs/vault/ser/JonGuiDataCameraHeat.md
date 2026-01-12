---
id: ser.JonGuiDataCameraHeat
proto: jon_shared_data_camera_heat.proto
package: ser
type: message
---

# JonGuiDataCameraHeat

**Source:** `jon_shared_data_camera_heat.proto`

## Description

Thermal camera state including zoom, focus, AGC mode, and filter settings. Provides real-time feedback on thermal imaging settings, enhancement levels, and field of view calculations.

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

## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Update Rate:** Real-time

### Purpose

Provides real-time state information for the thermal camera subsystem. UI components should display current zoom/focus positions, AGC mode, color filter, enhancement settings (DDE, CLAHE), and field of view information.

### Related Commands

- [[cmd.HeatCamera.Zoom]] - Controls zoom position
- [[cmd.HeatCamera.FocusIn]] - Controls focus
- [[cmd.HeatCamera.FocusOut]] - Controls focus
- [[cmd.HeatCamera.SetAutoFocus]] - Controls auto-focus mode
- [[cmd.HeatCamera.SetAGC]] - Controls AGC mode
- [[cmd.HeatCamera.SetFilters]] - Controls color filter
- [[cmd.HeatCamera.SetDDELevel]] - Controls DDE enhancement
- [[cmd.HeatCamera.EnableDDE]] - Enables DDE
- [[cmd.HeatCamera.DisableDDE]] - Disables DDE
- [[cmd.HeatCamera.SetDigitalZoomLevel]] - Controls digital zoom
- [[cmd.HeatCamera.SetClaheLevel]] - Controls CLAHE enhancement
- [[cmd.HeatCamera.SetFxMode]] - Controls effects mode
- [[cmd.HeatCamera.Start]] - Starts camera
- [[cmd.HeatCamera.Stop]] - Stops camera

### Display Guidelines

Display zoom position as slider or percentage. Show AGC mode and filter as labeled indicators. Display DDE and CLAHE levels when enabled. Show zoom table position as discrete steps. FOV values provide situational awareness. Auto-focus state should be clearly indicated.

## Field Notes

### zoom_pos (#1)

Current zoom position (normalized).

#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`

### agc_mode (#2)

Current Automatic Gain Control mode.

#### Metadata

- **Semantic Type:** :enum
- **Display Format:** Show mode name (e.g., "Auto", "Manual", "Linear")

### filter (#3)

Current color palette/filter applied to thermal image.

#### Metadata

- **Semantic Type:** :enum
- **Display Format:** Show filter name (e.g., "White Hot", "Black Hot", "Rainbow")

### auto_focus (#4)

Auto-focus enabled state.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "AF: ON/OFF" or toggle indicator

### zoom_table_pos (#5)

Current discrete zoom position index.

#### Metadata

- **Semantic Type:** :index
- **Display Format:** `{zoom_table_pos} / {zoom_table_pos_max}`

### zoom_table_pos_max (#6)

Maximum zoom table position index.

#### Metadata

- **Semantic Type:** :count
- **Display Format:** Used in position display

### dde_level (#7)

Digital Detail Enhancement level (0-512).

#### Metadata

- **Semantic Type:** :level
- **Unit:** -
- **Precision:** 0
- **Display Format:** `DDE: {value}`

### dde_enabled (#8)

DDE (Digital Detail Enhancement) enabled state.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "DDE: ON/OFF" or toggle indicator

### digital_zoom_level (#10)

Current digital zoom multiplier.

#### Metadata

- **Semantic Type:** :multiplier
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

- **Semantic Type:** :status-flag
- **Display Format:** Show as "Camera: Started/Stopped" or status indicator



