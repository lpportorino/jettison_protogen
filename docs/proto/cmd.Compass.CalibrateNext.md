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



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :poll-confirm


### Purpose

Advances to next step in compass calibration sequence


### Related State

- [[proto/ser.JonGuiDataCompassCalibration]]


### Related Commands

- [[proto/cmd.Compass.CalibrateStartLong]]
- [[proto/cmd.Compass.CalibrateStartShort]]
- [[proto/cmd.Compass.CalibrateCencel]]


### Preconditions

- Calibration must be in progress




