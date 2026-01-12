# FocusROI (cmd.HeatCamera.FocusROI)

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Command message for specific operation.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| x1 | double | 1 | - | >= -1.0, <= 1.0 |
| y1 | double | 2 | - | >= -1.0, <= 1.0 |
| x2 | double | 3 | - | >= -1.0, <= 1.0 |
| y2 | double | 4 | - | >= -1.0, <= 1.0 |
| frame_time | uint64 | 5 | TODO: Remove these fields after migration - now in Root message (fields 6-8) | - |
| state_time | uint64 | 6 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_heat_camera.proto` for complete context
