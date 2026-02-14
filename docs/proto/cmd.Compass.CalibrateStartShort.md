---
id: cmd.Compass.CalibrateStartShort
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# CalibrateStartShort

**Source:** `jon_shared_cmd_compass.proto`

## Description

Initiates a short (4-point) compass calibration procedure that requires the device to be positioned at 4 cardinal points instead of the full multi-point long calibration. The backend sends COMPASS_CALIBRATION_4POINT to the compass device for this faster but less precise calibration mode.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Initiates a quick 4-point compass calibration procedure as an alternative to the full 12-point long calibration. This mode is faster but provides less precise magnetic distortion correction compared to the comprehensive long calibration.

### Related State

- [[proto/ser.JonGuiDataCompassCalibration]] - Tracks calibration status, current stage, and final stage count
- [[proto/ser.JonGuiDataCompassCalibrateStatus]] - Enum indicating CALIBRATING_SHORT when active

### Related Commands

- [[proto/cmd.Compass.CalibrateStartLong]] - Full 12-point calibration (more precise, slower)
- [[proto/cmd.Compass.CalibrateNext]] - Advances to next calibration stage
- [[proto/cmd.Compass.CalibrateCencel]] - Cancels ongoing calibration

### Preconditions

- Compass must be started (`compass.isStarted == true`)
- Calibration must not already be in progress

### Implementation Notes

No parameters required. Invoked via `calibrateShortStart()` function in frontend. The backend sends `COMPASS_CALIBRATION_4POINT` to the compass device via CAN bus. During calibration, device must be rotated to 4 cardinal positions (N, E, S, W). Progress is tracked via `stage` and `finalStage` fields in compass calibration state.

<!-- NEEDS_REVIEW: Verify if short calibration is exposed in main UI or only in factory/control panel -->



