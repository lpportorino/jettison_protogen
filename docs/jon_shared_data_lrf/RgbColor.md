# RgbColor (ser.RgbColor)

**Source:** `jon_shared_data_lrf.proto`

## Description

State/data message.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| red | uint32 | 1 | - | >= 0, <= 255 |
| green | uint32 | 2 | - | >= 0, <= 255 |
| blue | uint32 | 3 | - | >= 0, <= 255 |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_lrf.proto` for complete context
