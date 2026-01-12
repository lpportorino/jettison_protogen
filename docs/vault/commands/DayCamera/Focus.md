---
id: cmd.DayCamera.Focus
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Focus

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Controls the day camera focus position. This message wraps multiple focus control operations in a oneof.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | set_value | [[cmd.DayCamera.SetValue]] | - |
| 2 | move | [[cmd.DayCamera.Move]] | - |
| 3 | halt | [[cmd.DayCamera.Halt]] | - |
| 4 | offset | [[cmd.DayCamera.Offset]] | - |
| 5 | reset_focus | [[cmd.DayCamera.ResetFocus]] | - |
| 6 | save_to_table_focus | [[cmd.DayCamera.SaveToTableFocus]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6

## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget

### Purpose

Sets the day camera focus position. The set_value field contains a normalized value (0.0-1.0) representing the focus position.

### Related State

- [[ser.JonGuiDataCameraDay]]

### Implementation Notes

Use the set_value field with a normalized value between 0.0 (near focus) and 1.0 (far focus/infinity). Other fields in the oneof provide alternative focus control methods like continuous move or incremental offset.

## Field Notes

### set_value (#1)

Normalized focus position (0.0 = near, 1.0 = infinity).

#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`




