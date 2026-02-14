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

- **Category:** :settings
- **UI Pattern:** :state-machine-menu
- **Feedback:** :fire-and-forget


### Purpose

Root command container that dispatches to compass/magnetometer sub-commands. Routes lifecycle commands (start/stop), calibration workflow commands, and configuration settings to the compass module. The compass provides azimuth/elevation readings for system orientation.


### Related State

- [[proto/ser.JonGuiDataCompass]] - Current compass readings (azimuth, elevation, isStarted)
- [[proto/ser.JonGuiDataCompassCalibration]] - Calibration status and progress


### Related Commands

- [[proto/cmd.Compass.Start]] - Start compass sensor
- [[proto/cmd.Compass.Stop]] - Stop compass sensor
- [[proto/cmd.Compass.CalibrateStartLong]] - Begin long calibration procedure
- [[proto/cmd.Compass.CalibrateStartShort]] - Begin short calibration procedure
- [[proto/cmd.Compass.CalibrateNext]] - Advance to next calibration step
- [[proto/cmd.Compass.CalibrateCencel]] - Cancel ongoing calibration
- [[proto/cmd.Compass.SetMagneticDeclination]] - Set magnetic declination offset
- [[proto/cmd.Compass.SetOffsetAngleAzimuth]] - Set azimuth offset angle
- [[proto/cmd.Compass.SetOffsetAngleElevation]] - Set elevation offset angle
- [[proto/cmd.Compass.SetUseRotaryPosition]] - Toggle rotary position usage
- [[proto/cmd.Compass.GetMeteo]] - Request meteo data from compass sensor


### Implementation Notes

Oneof wrapper routing commands through cmd_server to the compass module. Commands are dispatched based on which field is set in the oneof. The compass module communicates with hardware via CAN bus (IDs 0x304/0x305 TX, 0x314/0x315 RX).



## Field Notes


### start (#1)

Start the compass sensor. Powers on the magnetometer hardware and begins providing azimuth/elevation readings. See [[proto/cmd.Compass.Start]].

- **Semantic Type:** :action

### stop (#2)

Stop the compass sensor. Powers down the magnetometer hardware. See [[proto/cmd.Compass.Stop]].

- **Semantic Type:** :action

### set_magnetic_declination (#3)

Configure magnetic declination compensation. Adjusts compass readings to account for the difference between magnetic and true north at the current location. See [[proto/cmd.Compass.SetMagneticDeclination]].

- **Semantic Type:** :angle

### set_offset_angle_azimuth (#4)

Set azimuth offset angle in mils. Compensates for physical mounting orientation. See [[proto/cmd.Compass.SetOffsetAngleAzimuth]].

- **Semantic Type:** :angle

### set_offset_angle_elevation (#5)

Set elevation offset angle in mils. Compensates for physical mounting tilt. See [[proto/cmd.Compass.SetOffsetAngleElevation]].

- **Semantic Type:** :angle

### set_use_rotary_position (#6)

Toggle whether compass uses rotary turret position data for enhanced accuracy. When enabled, combines magnetometer readings with rotary encoder data. See [[proto/cmd.Compass.SetUseRotaryPosition]].

- **Semantic Type:** :toggle-state


### start_calibrate_long (#7)

Initiates the long (full) calibration procedure. Requires rotating the device in all directions to collect magnetometer samples. Monitor progress via [[proto/ser.JonGuiDataCompassCalibration]].

- **Semantic Type:** :action


### start_calibrate_short (#8)

Initiates a short (quick) calibration procedure. Less comprehensive than long calibration but faster. See [[proto/cmd.Compass.CalibrateStartShort]].

- **Semantic Type:** :action

### calibrate_next (#9)

Advances to the next step in an active calibration procedure. Used during multi-step calibration workflows. See [[proto/cmd.Compass.CalibrateNext]].

- **Semantic Type:** :action

### calibrate_cencel (#10)

Cancels an ongoing calibration procedure. Aborts the calibration workflow without saving results. See [[proto/cmd.Compass.CalibrateCencel]].

- **Semantic Type:** :action
<!-- NEEDS_REVIEW: Field name "calibrate_cencel" appears to be a typo for "calibrate_cancel" -->

### get_meteo (#11)

Request meteorological data from the compass sensor. The compass module includes internal temperature, pressure, and humidity sensors. See [[proto/cmd.Compass.GetMeteo]].

- **Semantic Type:** :action



