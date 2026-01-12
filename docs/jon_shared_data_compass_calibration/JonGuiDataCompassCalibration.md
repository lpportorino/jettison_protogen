# JonGuiDataCompassCalibration (ser.JonGuiDataCompassCalibration)

**Source:** `jon_shared_data_compass_calibration.proto`

## Description

State data for CompassCalibration subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| stage | uint32 | 1 | - | >= 0 |
| final_stage | uint32 | 2 | - | > 0 |
| target_azimuth | double | 3 | - | >= 0, < 360 |
| target_elevation | double | 4 | - | >= -90, <= 90 |
| target_bank | double | 5 | - | >= -180, < 180 |
| status | JonGuiDataCompassCalibrateStatus | 6 | - | must not be 0/UNSPECIFIED, must be defined enum value |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_compass_calibration.proto` for complete context
