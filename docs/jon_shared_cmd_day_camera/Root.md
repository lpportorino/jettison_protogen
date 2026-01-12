# Root (cmd.DayCamera.Root)

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| focus | Focus | 1 | - | - |
| zoom | Zoom | 2 | - | - |
| set_iris | SetIris | 3 | - | - |
| set_infra_red_filter | SetInfraRedFilter | 4 | - | - |
| start | Start | 5 | - | - |
| stop | Stop | 6 | - | - |
| photo | Photo | 7 | - | - |
| set_auto_iris | SetAutoIris | 8 | - | - |
| halt_all | HaltAll | 9 | - | - |
| set_fx_mode | SetFxMode | 10 | - | - |
| next_fx_mode | NextFxMode | 11 | - | - |
| prev_fx_mode | PrevFxMode | 12 | - | - |
| get_meteo | GetMeteo | 13 | - | - |
| refresh_fx_mode | RefreshFxMode | 14 | - | - |
| set_digital_zoom_level | SetDigitalZoomLevel | 15 | - | - |
| set_clahe_level | SetClaheLevel | 16 | - | - |
| shift_clahe_level | ShiftClaheLevel | 17 | - | - |
| focus_roi | FocusROI | 18 | - | - |
| track_roi | TrackROI | 19 | - | - |
| zoom_roi | ZoomROI | 20 | - | - |
| fx_roi | FxROI | 21 | - | - |
| set_auto_gain | SetAutoGain | 22 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_day_camera.proto` for complete context
