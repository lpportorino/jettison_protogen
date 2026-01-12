# SetChannel (cmd.Power.SetChannel)

**Source:** `jon_shared_cmd_power.proto`

## Description

Sets a configuration parameter or value.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| channel | uint32 | 1 | - | <= 7 |
| power_on | bool | 2 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_power.proto` for complete context
