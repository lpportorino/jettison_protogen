---
id: ser.JonGuiDataCompassCalibration
proto: jon_shared_data_compass_calibration.proto
package: ser
type: message
---

# JonGuiDataCompassCalibration

**Source:** `jon_shared_data_compass_calibration.proto`

## Description

Represents the current state and progress of a compass calibration process, tracking the current step (stage), total steps required (final_stage), target orientation angles the user should point toward, and the overall calibration status.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | stage | uint32 | >= 0 |
| 2 | final_stage | uint32 | > 0 |
| 3 | target_azimuth | double | >= 0, < 360 |
| 4 | target_elevation | double | >= -90, <= 90 |
| 5 | target_bank | double | >= -180, < 180 |
| 6 | status | [[proto/ser.JonGuiDataCompassCalibrateStatus]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Compass calibration state and progress



### Related Commands

- [[proto/cmd.Compass.CalibrateStartLong]]
- [[proto/cmd.Compass.CalibrateStartShort]]
- [[proto/cmd.Compass.CalibrateNext]]
- [[proto/cmd.Compass.CalibrateCencel]]



### Implementation Notes

Contains calibration step, progress, and result data



## Field Notes


### stage (#1)

Current calibration stage


### final_stage (#2)

Total calibration stages


### target_azimuth (#3)

Azimuth angle in degrees (0=North, clockwise)


### target_elevation (#4)

Elevation angle in degrees


### target_bank (#5)

Bank/roll angle in degrees


### status (#6)

See related enum for valid values


### figure_of_merit_raw (#7)

Two raw bytes returned by the DMC-pico after calculating compensation parameters, packed as a uint16 (low byte first) and zero-extended into uint32. Per vendor manual TML 913755 §4.1.4 the FOM is a degrees value (typical 0.2-0.3, recommended < 0.5, device discards results > 9.9). The binary CAN-UART bridge encoding is undocumented; consumers must decode empirically. Value 0 means no FOM has been reported yet for the current session.



