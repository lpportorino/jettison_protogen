# RotateToNDC (cmd.RotaryPlatform.RotateToNDC)

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Controls rotary platform rotation.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| channel | ser.JonGuiDataVideoChannel | 1 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| x | double | 2 | - | >= -1.0, <= 1.0 |
| y | double | 3 | - | >= -1.0, <= 1.0 |
| frame_time | uint64 | 4 | - | - |
| state_time | uint64 | 5 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_rotary.proto` for complete context
