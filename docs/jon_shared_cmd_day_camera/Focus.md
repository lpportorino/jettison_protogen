# Focus (cmd.DayCamera.Focus)

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Command message for specific operation.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| set_value | SetValue | 1 | - | - |
| move | Move | 2 | - | - |
| halt | Halt | 3 | - | - |
| offset | Offset | 4 | - | - |
| reset_focus | ResetFocus | 5 | - | - |
| save_to_table_focus | SaveToTableFocus | 6 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_day_camera.proto` for complete context
