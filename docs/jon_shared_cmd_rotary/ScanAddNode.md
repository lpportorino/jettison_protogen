# ScanAddNode (cmd.RotaryPlatform.ScanAddNode)

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Scanning pattern control command.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| index | int32 | 1 | - | >= 0 |
| DayZoomTableValue | int32 | 2 | - | >= 0 |
| HeatZoomTableValue | int32 | 3 | - | >= 0 |
| azimuth | double | 4 | - | >= 0, < 360 |
| elevation | double | 5 | - | >= -90, <= 90 |
| linger | double | 6 | - | >= 0 |
| speed | double | 7 | - | > 0.0, <= 1.0 |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_rotary.proto` for complete context
