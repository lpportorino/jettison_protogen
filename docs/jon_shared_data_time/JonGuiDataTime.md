# JonGuiDataTime (ser.JonGuiDataTime)

**Source:** `jon_shared_data_time.proto`

## Description

State/data message.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| timestamp | int64 | 1 | - | >= 0 |
| manual_timestamp | int64 | 2 | - | >= 0 |
| zone_id | int32 | 3 | - | - |
| use_manual_time | bool | 4 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_time.proto` for complete context
