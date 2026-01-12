# SetAzimuthValue (cmd.RotaryPlatform.SetAzimuthValue)

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Sets a configuration parameter or value.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| value | double | 1 | - | >= 0.0, < 360.0 |
| direction | ser.JonGuiDataRotaryDirection | 2 | - | must not be 0/UNSPECIFIED, must be defined enum value |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_rotary.proto` for complete context
