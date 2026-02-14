---
id: cmd.Compass.CalibrateStartLong
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# CalibrateStartLong

**Source:** `jon_shared_cmd_compass.proto`

## Description

Initiates the long (comprehensive) compass calibration procedure, which guides the user through multiple stages of rotating the device to different orientations to correct for local magnetic field distortions. This is a multi-stage process (12-point) that compensates for hard-iron and soft-iron distortions.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Initiates the comprehensive 12-point compass calibration procedure that compensates for hard-iron and soft-iron magnetic field distortions. Used when the device has been moved to a new location with different local magnetic interference or when compass readings are inaccurate.


### Related State

- [[proto/ser.JonGuiDataCompassCalibration]]
- [[proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/cmd.Compass.CalibrateStartShort]]
- [[proto/cmd.Compass.CalibrateNext]]
- [[proto/cmd.Compass.CalibrateCencel]]


### Preconditions

- Compass must be started (`isStarted: true`)
- Not currently calibrating


### Implementation Notes

Multi-step calibration process requiring user to rotate the device through multiple orientations. The UI displays a "Start Calibration" button that sends this command via `calibrateLongStart()`. After sending, the button shows pending state for 2 seconds or until the `compassCalibration.status` changes to `CALIBRATING_LONG`. Progress is tracked via `stage/finalStage` fields in `JonGuiDataCompassCalibration`.



