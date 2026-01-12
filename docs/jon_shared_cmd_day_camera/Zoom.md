# Zoom (cmd.DayCamera.Zoom)

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Command message for specific operation.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| set_value | SetValue | 1 | - | - |
| move | Move | 2 | - | - |
| halt | Halt | 3 | - | - |
| set_zoom_table_value | SetZoomTableValue | 4 | - | - |
| next_zoom_table_pos | NextZoomTablePos | 5 | - | - |
| prev_zoom_table_pos | PrevZoomTablePos | 6 | - | - |
| offset | Offset | 7 | - | - |
| reset_zoom | ResetZoom | 8 | - | - |
| save_to_table | SaveToTable | 9 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_day_camera.proto` for complete context
