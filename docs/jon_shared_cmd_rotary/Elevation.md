# Elevation (cmd.RotaryPlatform.Elevation)

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Command message for specific operation.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| set_value | SetElevationValue | 1 | - | - |
| rotate_to | RotateElevationTo | 2 | - | - |
| rotate | RotateElevation | 3 | - | - |
| relative | RotateElevationRelative | 4 | - | - |
| relative_set | RotateElevationRelativeSet | 5 | - | - |
| halt | HaltElevation | 6 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_rotary.proto` for complete context
