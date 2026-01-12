# SetMode (cmd.RotaryPlatform.SetMode)

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Sets a configuration parameter or value.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| mode | ser.JonGuiDataRotaryMode | 1 | - | must not be 0/UNSPECIFIED, must be defined enum value |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_rotary.proto` for complete context
