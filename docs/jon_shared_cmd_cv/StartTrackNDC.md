# StartTrackNDC (cmd.CV.StartTrackNDC)

**Source:** `jon_shared_cmd_cv.proto`

## Description

Command message for specific operation.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| channel | ser.JonGuiDataVideoChannel | 1 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| x | double | 2 | - | >= -1.0, <= 1.0 |
| y | double | 3 | - | >= -1.0, <= 1.0 |
| frame_time | uint64 | 4 | TODO: Remove these fields after migration - now in Root message (fields 6-8) | - |
| state_time | uint64 | 5 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_cv.proto` for complete context
