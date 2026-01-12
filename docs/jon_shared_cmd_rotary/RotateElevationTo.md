# RotateElevationTo (cmd.RotaryPlatform.RotateElevationTo)

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Controls rotary platform rotation.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| target_value | double | 1 | - | >= -90.0, <= 90.0 |
| speed | double | 2 | - | >= 0.0, <= 1.0 |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_rotary.proto` for complete context
