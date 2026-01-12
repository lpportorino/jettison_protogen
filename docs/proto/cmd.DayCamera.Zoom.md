---
id: cmd.DayCamera.Zoom
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Zoom

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Composite command for controlling day camera optical zoom through multiple methods: absolute value setting, continuous movement with speed control, halt, table-based positioning, offset adjustment, reset to default, and saving current position. Uses a required oneof with nine sub-commands for flexible zoom control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | set_value | [[proto/cmd.DayCamera.SetValue]] | - |
| 2 | move | [[proto/cmd.DayCamera.Move]] | - |
| 3 | halt | [[proto/cmd.DayCamera.Halt]] | - |
| 4 | set_zoom_table_value | [[proto/cmd.DayCamera.SetZoomTableValue]] | - |
| 5 | next_zoom_table_pos | [[proto/cmd.DayCamera.NextZoomTablePos]] | - |
| 6 | prev_zoom_table_pos | [[proto/cmd.DayCamera.PrevZoomTablePos]] | - |
| 7 | offset | [[proto/cmd.DayCamera.Offset]] | - |
| 8 | reset_zoom | [[proto/cmd.DayCamera.ResetZoom]] | - |
| 9 | save_to_table | [[proto/cmd.DayCamera.SaveToTable]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8, #9




## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider-with-steppers
- **Feedback:** :optimistic-visual


### Purpose

Controls day camera optical zoom through various methods


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.DayCamera.Focus]]


### Preconditions

- Day camera started


### Implementation Notes

Composite message supporting multiple zoom control patterns: absolute value, continuous move, halt, table positions, offset, reset



