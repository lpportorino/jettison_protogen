# JonGuiDataDayCamGlassHeater (ser.JonGuiDataDayCamGlassHeater)

**Source:** `jon_shared_data_day_cam_glass_heater.proto`

## Description

State/data message.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| temperature | double | 1 | - | >= -273.15, <= 660.32 |
| status | bool | 2 | - | - |
| is_started | bool | 3 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_day_cam_glass_heater.proto` for complete context
