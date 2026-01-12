# SetTimeAndZone (cmd.System.SetTimeAndZone)

**Source:** `jon_shared_cmd_system.proto`

## Description

Sets a configuration parameter or value.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| timestamp | int64 | 1 | - | >= 0 |
| zone_id | int32 | 2 | - | >= 0, < 595 |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_system.proto` for complete context
