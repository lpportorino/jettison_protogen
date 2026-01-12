# Root (cmd.HeatCamera.Root)

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| zoom | Zoom | 1 | - | - |
| set_agc | SetAGC | 2 | - | - |
| set_filter | SetFilters | 3 | - | - |
| start | Start | 4 | - | - |
| stop | Stop | 5 | - | - |
| photo | Photo | 6 | - | - |
| zoom_in | ZoomIn | 10 | - | - |
| zoom_out | ZoomOut | 11 | - | - |
| zoom_stop | ZoomStop | 12 | - | - |
| focus_in | FocusIn | 13 | - | - |
| focus_out | FocusOut | 14 | - | - |
| focus_stop | FocusStop | 15 | - | - |
| calibrate | Calibrate | 16 | - | - |
| set_dde_level | SetDDELevel | 17 | - | - |
| enable_dde | EnableDDE | 18 | - | - |
| disable_dde | DisableDDE | 19 | - | - |
| set_auto_focus | SetAutoFocus | 20 | - | - |
| focus_step_plus | FocusStepPlus | 21 | - | - |
| focus_step_minus | FocusStepMinus | 22 | - | - |
| set_fx_mode | SetFxMode | 23 | - | - |
| next_fx_mode | NextFxMode | 24 | - | - |
| prev_fx_mode | PrevFxMode | 25 | - | - |
| get_meteo | GetMeteo | 26 | - | - |
| shift_dde | ShiftDDE | 27 | - | - |
| refresh_fx_mode | RefreshFxMode | 28 | - | - |
| reset_zoom | ResetZoom | 29 | - | - |
| save_to_table | SaveToTable | 30 | - | - |
| set_calib_mode | SetCalibMode | 31 | - | - |
| set_digital_zoom_level | SetDigitalZoomLevel | 32 | - | - |
| set_clahe_level | SetClaheLevel | 33 | - | - |
| shift_clahe_level | ShiftClaheLevel | 34 | - | - |
| focus_roi | FocusROI | 35 | - | - |
| track_roi | TrackROI | 36 | - | - |
| zoom_roi | ZoomROI | 37 | - | - |
| fx_roi | FxROI | 38 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_heat_camera.proto` for complete context
