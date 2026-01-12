# SetLocalization (cmd.System.SetLocalization)

**Source:** `jon_shared_cmd_system.proto`

## Description

Sets a configuration parameter or value.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| loc | ser.JonGuiDataSystemLocalizations | 1 | - | must not be 0/UNSPECIFIED, must be defined enum value |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_system.proto` for complete context
