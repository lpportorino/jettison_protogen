# Move (cmd.DayCamera.Move)

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Command message for specific operation.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| target_value | double | 1 | - | >= 0.0, <= 1.0 |
| speed | double | 2 | - | >= 0.0, <= 1.0 |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_day_camera.proto` for complete context
