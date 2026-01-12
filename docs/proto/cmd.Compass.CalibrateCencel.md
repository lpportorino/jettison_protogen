---
id: cmd.Compass.CalibrateCencel
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# CalibrateCencel

**Source:** `jon_shared_cmd_compass.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCompassCalibration]]


### Related Commands

- [[proto/proto/cmd.Compass.CalibrateStartLong]]
- [[proto/proto/cmd.Compass.CalibrateStartShort]]
- [[proto/proto/cmd.Compass.CalibrateNext]]


### Preconditions

- Calibration in progress


### Implementation Notes

Part of compass calibration state machine UI



