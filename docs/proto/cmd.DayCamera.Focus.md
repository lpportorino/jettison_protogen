---
id: cmd.DayCamera.Focus
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Focus

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Composite command for controlling day camera focus operations, supporting direct value setting, continuous movement with variable speed, halting, offset adjustment, reset to table-stored value, and saving current position to the focus table. Uses a required oneof with six sub-commands. The focus position is normalized (0.0-1.0 range) and persisted to a zoom-indexed lookup table for automatic focus recall when the zoom level changes.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | set_value | [[proto/cmd.DayCamera.SetValue]] | - |
| 2 | move | [[proto/cmd.DayCamera.Move]] | - |
| 3 | halt | [[proto/cmd.DayCamera.Halt]] | - |
| 4 | offset | [[proto/cmd.DayCamera.Offset]] | - |
| 5 | reset_focus | [[proto/cmd.DayCamera.ResetFocus]] | - |
| 6 | save_to_table_focus | [[proto/cmd.DayCamera.SaveToTableFocus]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6




## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :pending-timeout
- **Timeout (ms):** 2000


### Purpose

Controls day camera focus operations (set, move, halt, offset, reset, save) with press-and-hold acceleration for continuous movement and single-click step adjustment for fine control.


### Related State

- [[proto/ser.JonGuiDataCameraDay]] - `focusPos` field reflects current normalized focus position


### Related Commands

- [[proto/cmd.CV.SetAutoFocus]] - Enable/disable autofocus (disables manual focus buttons when active)
- [[proto/cmd.DayCamera.Zoom]] - Related lens control with parallel structure


### Preconditions

- Day camera started
- Auto-focus disabled for manual control (set_value, move, offset)


### Implementation Notes

The frontend implements a two-phase focus pattern:
1. **Initial press**: Sends `offset` command with small step (5% of range direction)
2. **Hold acceleration**: After initial offset, starts interval timer sending `move` commands with ramping speed (0 to 1 over 2 seconds)
3. **Release**: Sends `halt` to stop motor movement (only if move commands were sent)

The `jon-day-focus-mover` component provides finer control with configurable step sizes (0.001% to 10%) using only `offset` commands, plus Save/Reset action buttons.



## Field Notes


### set_value (#1)

Direct absolute positioning of focus. See [[proto/cmd.DayCamera.SetValue]] for normalized value constraints (0.0-1.0).


### move (#2)

Continuous movement toward target position at specified speed. Used for press-and-hold focus adjustment with ramping acceleration. See [[proto/cmd.DayCamera.Move]].


### halt (#3)

Immediately stops focus motor movement. Called on button release after move commands. See [[proto/cmd.DayCamera.Halt]].


### offset (#4)

Relative step adjustment. Used for single-click fine focus control. Frontend uses step sizes from 0.00001 (0.001%) to 0.1 (10%) of the full range. See [[proto/cmd.DayCamera.Offset]].


### reset_focus (#5)

Restores focus to the value stored in the focus lookup table for the current zoom position. See [[proto/cmd.DayCamera.ResetFocus]].


### save_to_table_focus (#6)

Saves current focus position to the focus lookup table, indexed by current zoom level. Enables automatic focus recall when returning to this zoom level. See [[proto/cmd.DayCamera.SaveToTableFocus]].



