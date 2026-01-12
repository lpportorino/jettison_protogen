---
id: cmd.DayCamera.Focus
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Focus

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.SetAutoFocus]]
- [[proto/proto/cmd.DayCamera.SetAutoGain]]



### Implementation Notes

Composite command with multiple focus control sub-commands



