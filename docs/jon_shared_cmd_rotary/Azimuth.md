# Azimuth (cmd.RotaryPlatform.Azimuth)

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Command message for specific operation.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| set_value | SetAzimuthValue | 1 | - | - |
| rotate_to | RotateAzimuthTo | 2 | - | - |
| rotate | RotateAzimuth | 3 | - | - |
| relative | RotateAzimuthRelative | 4 | - | - |
| relative_set | RotateAzimuthRelativeSet | 5 | - | - |
| halt | HaltAzimuth | 6 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_rotary.proto` for complete context
