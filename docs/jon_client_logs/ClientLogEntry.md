# ClientLogEntry (jon.logs.ClientLogEntry)

**Source:** `jon_client_logs.proto`

## Description

Client logging data.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| lvl | string | 1 | Core log data (validated on ingress) | - |
| mod | string | 2 | - | - |
| msg | string | 3 | - | - |
| ts | int64 | 4 | - | - |
| file | string | 5 | Source location | - |
| line | int32 | 6 | - | - |
| sid | string | 7 | Session/client info | - |
| ua | string | 8 | - | - |
| url | optional string | 9 | - | - |
| origin | optional string | 10 | - | - |
| commit | string | 11 | - | - |
| build | string | 12 | - | - |
| sw | int32 | 13 | Device info | - |
| sh | int32 | 14 | - | - |
| dpr | double | 15 | - | - |
| lang | string | 16 | - | - |
| tz | string | 17 | - | - |
| extra | string | 18 | - | - |
| state_snapshot | optional bytes | 19 | Raw state snapshot bytes - NOT validated, stored as-is, decode later | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_client_logs.proto` for complete context
