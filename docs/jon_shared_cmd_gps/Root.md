# Root (cmd.Gps.Root)

**Source:** `jon_shared_cmd_gps.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| start | Start | 1 | - | - |
| stop | Stop | 2 | - | - |
| set_manual_position | SetManualPosition | 3 | - | - |
| set_use_manual_position | SetUseManualPosition | 4 | - | - |
| get_meteo | GetMeteo | 5 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_gps.proto` for complete context
