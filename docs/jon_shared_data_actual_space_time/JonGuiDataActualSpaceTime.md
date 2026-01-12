# JonGuiDataActualSpaceTime (ser.JonGuiDataActualSpaceTime)

**Source:** `jon_shared_data_actual_space_time.proto`

## Description

State/data message.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| azimuth | double | 1 | - | >= 0.0, < 360.0 |
| elevation | double | 2 | - | >= -90.0, <= 90.0 |
| bank | double | 3 | - | >= -180.0, < 180.0 |
| latitude | double | 4 | - | >= -90.0, <= 90.0 |
| longitude | double | 5 | - | >= -180.0, < 180.0 |
| altitude | double | 6 | - | >= -430.0, <= 100000.0 |
| timestamp | int64 | 7 | - | >= 0 |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_actual_space_time.proto` for complete context
