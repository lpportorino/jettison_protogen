# JonGuiDataGps (ser.JonGuiDataGps)

**Source:** `jon_shared_data_gps.proto`

## Description

State data for Gps subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| longitude | double | 1 | - | >= -180.0, <= 180.0 |
| latitude | double | 2 | - | >= -90.0, <= 90.0 |
| altitude | double | 3 | - | >= -430.0, <= 100000.0 |
| manual_longitude | double | 4 | - | >= -180.0, <= 180.0 |
| manual_latitude | double | 5 | - | >= -90.0, <= 90.0 |
| manual_altitude | double | 6 | - | >= -430.0, <= 100000.0 |
| fix_type | JonGuiDataGpsFixType | 7 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| use_manual | bool | 8 | - | - |
| timestamp | int64 | 9 | - | - |
| is_started | bool | 10 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_gps.proto` for complete context
