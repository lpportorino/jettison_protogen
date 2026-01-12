---
id: cmd.Compass.Root
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# Root

**Source:** `jon_shared_cmd_compass.proto`

## Description

Root command container that wraps all compass/magnetometer commands using a oneof pattern, enabling control of compass power state, calibration processes, and configuration settings. Commands are routed through the cmd_server to the compass module which communicates with the hardware via UART bridge.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start | [[proto/cmd.Compass.Start]] | - |
| 2 | stop | [[proto/cmd.Compass.Stop]] | - |
| 3 | set_magnetic_declination | [[proto/cmd.Compass.SetMagneticDeclination]] | - |
| 4 | set_offset_angle_azimuth | [[proto/cmd.Compass.SetOffsetAngleAzimuth]] | - |
| 5 | set_offset_angle_elevation | [[proto/cmd.Compass.SetOffsetAngleElevation]] | - |
| 6 | set_use_rotary_position | [[proto/cmd.Compass.SetUseRotaryPosition]] | - |
| 7 | start_calibrate_long | [[proto/cmd.Compass.CalibrateStartLong]] | - |
| 8 | start_calibrate_short | [[proto/cmd.Compass.CalibrateStartShort]] | - |
| 9 | calibrate_next | [[proto/cmd.Compass.CalibrateNext]] | - |
| 10 | calibrate_cencel | [[proto/cmd.Compass.CalibrateCencel]] | - |
| 11 | get_meteo | [[proto/cmd.Compass.GetMeteo]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8, #9, #10, #11




## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :state-machine-menu


### Purpose

Root message container for compass/magnetometer commands


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCompass]]
- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCompassCalibration]]




### Implementation Notes

Oneof wrapper containing start, stop, setMagneticDeclination, setOffsetAngleAzimuth, setOffsetAngleElevation, calibration commands, etc.



