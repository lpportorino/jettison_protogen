# JonGuiDataCompass (ser.JonGuiDataCompass)

**Source:** `jon_shared_data_compass.proto`

## Description

State data for Compass subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| azimuth | double | 1 | - | >= 0, < 360 |
| elevation | double | 2 | - | >= -90, <= 90 |
| bank | double | 3 | - | >= -180, < 180 |
| offsetAzimuth | double | 4 | - | >= -180, < 180 |
| offsetElevation | double | 5 | - | >= -90, <= 90 |
| magneticDeclination | double | 6 | - | >= -180, < 180 |
| calibrating | bool | 7 | - | - |
| is_started | bool | 8 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_compass.proto` for complete context
