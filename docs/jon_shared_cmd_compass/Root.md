# Root (cmd.Compass.Root)

**Source:** `jon_shared_cmd_compass.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| start | Start | 1 | - | - |
| stop | Stop | 2 | - | - |
| set_magnetic_declination | SetMagneticDeclination | 3 | - | - |
| set_offset_angle_azimuth | SetOffsetAngleAzimuth | 4 | - | - |
| set_offset_angle_elevation | SetOffsetAngleElevation | 5 | - | - |
| set_use_rotary_position | SetUseRotaryPosition | 6 | - | - |
| start_calibrate_long | CalibrateStartLong | 7 | - | - |
| start_calibrate_short | CalibrateStartShort | 8 | - | - |
| calibrate_next | CalibrateNext | 9 | - | - |
| calibrate_cencel | CalibrateCencel | 10 | - | - |
| get_meteo | GetMeteo | 11 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_compass.proto` for complete context
