# SetAutoFocus (cmd.CV.SetAutoFocus)

**Source:** `jon_shared_cmd_cv.proto`

## Description

Sets a configuration parameter or value.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| channel | ser.JonGuiDataVideoChannel | 1 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| value | bool | 2 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_cv.proto` for complete context
