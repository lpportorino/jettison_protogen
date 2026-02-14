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
- **Feedback:** :pending-timeout
- **Timeout (ms):** 2000


### Purpose

Controls day camera optical zoom through various methods: direct value setting, continuous movement with speed control, table-based preset positions, fine offset adjustment, and persistence to zoom table.


### Related State

- [[proto/ser.JonGuiDataCameraDay]] - `zoomPos` field reflects current normalized zoom position (0.0-1.0)


### Related Commands

- [[proto/cmd.DayCamera.Focus]] - Related lens control with parallel structure
- [[proto/cmd.DayCamera.SetDigitalZoomLevel]] - Digital zoom (post-capture scaling, separate from optical)
- [[proto/cmd.HeatCamera.Zoom]] - Thermal camera zoom (often synced with day camera via UI toggle)


### Preconditions

- Day camera started


### Implementation Notes

The frontend provides three zoom UI components:

1. **jon-zoom-ui**: Dual-camera zoom panel with sync toggle
   - `setZoomTableValue(index)` for preset positions (1-4)
   - Optional sync mode applies zoom to both day and heat cameras simultaneously

2. **jon-day-zoom-mover**: Fine zoom control with step sizes
   - Uses `offset` for step adjustments (0.001% to 10% of range)
   - Save button calls `saveToTable` to persist position
   - Reset button calls `resetZoom` to restore table value

3. **Mouse wheel/hotkey zoom**: Quick zoom in/out via `offset` commands



## Field Notes


### set_value (#1)

Direct absolute positioning of optical zoom. See [[proto/cmd.DayCamera.SetValue]] for normalized value constraints (0.0-1.0).


### move (#2)

Continuous movement toward target position at specified speed. Used for press-and-hold zoom adjustment with ramping acceleration. See [[proto/cmd.DayCamera.Move]].


### halt (#3)

Immediately stops zoom motor movement. Called on button release after move commands. See [[proto/cmd.DayCamera.Halt]].


### set_zoom_table_value (#4)

Jump to a preset zoom position by table index. The zoom table contains 4 preset positions. See [[proto/cmd.DayCamera.SetZoomTableValue]].


### next_zoom_table_pos (#5)

Increment to the next zoom table position (cycles through 4 presets). See [[proto/cmd.DayCamera.NextZoomTablePos]].


### prev_zoom_table_pos (#6)

Decrement to the previous zoom table position (cycles through 4 presets). See [[proto/cmd.DayCamera.PrevZoomTablePos]].


### offset (#7)

Relative step adjustment for fine zoom control. Frontend uses step sizes from 0.00001 (0.001%) to 0.1 (10%) of the full range. See [[proto/cmd.DayCamera.Offset]].


### reset_zoom (#8)

Restores zoom to the value stored in the zoom lookup table for the current table position. See [[proto/cmd.DayCamera.ResetZoom]].


### save_to_table (#9)

Saves current zoom position to the zoom lookup table at the current table index. Enables custom preset positions. See [[proto/cmd.DayCamera.SaveToTable]].



