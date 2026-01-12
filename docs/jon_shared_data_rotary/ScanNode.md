# ScanNode (ser.ScanNode)

**Source:** `jon_shared_data_rotary.proto`

## Description

State/data message.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| index | int32 | 1 | - | >= 0 |
| DayZoomTableValue | int32 | 2 | - | >= 0 |
| HeatZoomTableValue | int32 | 3 | - | >= 0 |
| azimuth | double | 4 | - | >= 0, < 360 |
| elevation | double | 5 | - | >= -90, <= 90 |
| linger | double | 6 | - | >= 0 |
| speed | double | 7 | - | > 0.0, <= 1.0 |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_rotary.proto` for complete context
