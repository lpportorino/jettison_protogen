# SetOriginGPS (cmd.RotaryPlatform.SetOriginGPS)

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Sets a configuration parameter or value.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| latitude | double | 1 | - | >= -90.0, <= 90.0 |
| longitude | double | 2 | - | >= -180.0, < 180.0 |
| altitude | double | 3 | - | >= -430.0, <= 100000.0 |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_rotary.proto` for complete context
