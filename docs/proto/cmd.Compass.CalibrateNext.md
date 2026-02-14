---
id: cmd.Compass.CalibrateNext
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# CalibrateNext

**Source:** `jon_shared_cmd_compass.proto`

## Description

Advances to the next step in an ongoing compass calibration sequence, signaling that the device has been positioned correctly for the current calibration stage. The backend automatically sends this command when using rotary platform positioning after the platform reaches the target position (within 5 degrees tolerance) and holds steady.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

*No fields - this is a trigger command with no arguments.*

## Field Notes

This command has no fields. It acts as a simple trigger to advance the calibration state machine to the next stage. The current calibration progress is tracked in [[proto/ser.JonGuiDataCompassCalibration]] via the `stage` and `finalStage` fields.

## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :poll-confirm


### Purpose

Advances to the next step in a multi-stage compass calibration sequence after the device has been correctly positioned for the current calibration stage.


### Related State

- [[proto/ser.JonGuiDataCompassCalibration]]


### Related Commands

- [[proto/cmd.Compass.CalibrateStartLong]]
- [[proto/cmd.Compass.CalibrateStartShort]]
- [[proto/cmd.Compass.CalibrateCencel]]


### Preconditions

- Calibration must be in progress (status is `CALIBRATING_LONG`)
- Device must be positioned at the target orientation specified by `targetAzimuth`, `targetElevation`, and `targetBank` in [[proto/ser.JonGuiDataCompassCalibration]]


### Implementation Notes

When using rotary platform positioning (`setUseRotaryPosition: true`), the backend automatically sends this command after the platform reaches the target position (within 5 degrees tolerance) and holds steady. Manual UI does not expose a "Next" button in this mode. For manual calibration, the user must position the device and explicitly trigger advancement.




