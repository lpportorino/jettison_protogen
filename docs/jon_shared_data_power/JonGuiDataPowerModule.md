# JonGuiDataPowerModule (ser.JonGuiDataPowerModule)

**Source:** `jon_shared_data_power.proto`

## Description

State data for PowerModule subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| voltage | double | 1 | - | >= 0, <= 100 |
| current | double | 2 | - | >= 0, <= 50 |
| power | double | 3 | - | >= 0, <= 500 |
| is_on | bool | 4 | - | - |
| has_alarm | bool | 5 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_power.proto` for complete context
