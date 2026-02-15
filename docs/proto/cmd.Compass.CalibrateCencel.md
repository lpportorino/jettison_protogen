---
id: cmd.Compass.CalibrateCencel
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# CalibrateCencel

**Source:** `jon_shared_cmd_compass.proto`

## Description

Cancels an ongoing compass calibration process, returning the compass to normal operation mode. This command terminates a calibration session initiated by CalibrateStartLong or CalibrateStartShort, typically used when the user wants to abort calibration before completion.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Cancels an ongoing compass calibration process


### Related State

- [[proto/ser.JonGuiDataCompassCalibration]]


### Related Commands

- [[proto/cmd.Compass.CalibrateStartLong]]
- [[proto/cmd.Compass.CalibrateStartShort]]
- [[proto/cmd.Compass.CalibrateNext]]


### Preconditions

- Calibration in progress


### Implementation Notes

Part of compass calibration state machine UI



