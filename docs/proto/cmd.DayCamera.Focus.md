---
id: cmd.DayCamera.Focus
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Focus

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Composite command for controlling day camera focus operations, supporting direct value setting, continuous movement, halting, offset adjustment, reset to table value, and saving current position to the focus table. Uses a required oneof with six sub-commands.

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
- **Feedback:** :fire-and-forget


### Purpose

Controls day camera focus operations (set, move, halt, offset, reset, save)


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/proto/proto/proto/cmd.DayCamera.SetAutoFocus]]
- [[proto/proto/proto/proto/proto/cmd.DayCamera.SetAutoGain]]



### Implementation Notes

Composite command with multiple focus control sub-commands



