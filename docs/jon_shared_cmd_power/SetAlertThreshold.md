# SetAlertThreshold (cmd.Power.SetAlertThreshold)

**Source:** `jon_shared_cmd_power.proto`

## Description

Sets a configuration parameter or value.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| channel | uint32 | 1 | - | <= 7 |
| threshold_ma | uint32 | 2 | - | <= 10000 |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_power.proto` for complete context
