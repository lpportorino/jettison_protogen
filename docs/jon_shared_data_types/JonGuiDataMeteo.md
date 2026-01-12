# JonGuiDataMeteo (ser.JonGuiDataMeteo)

**Source:** `jon_shared_data_types.proto`

## Description

Meteorological sensor data (temperature, humidity, pressure).

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| temperature | double | 1 | - | >= -273.15, <= 150.0 |
| humidity | double | 2 | - | >= 0.0, <= 100.0 |
| pressure | double | 3 | - | >= 0.0, <= 120000.0 |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_types.proto` for complete context
