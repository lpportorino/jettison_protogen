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

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Initiates short compass calibration procedure


### Related State

- [[proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/cmd.Compass.CalibrateStartLong]]
- [[proto/cmd.Compass.CalibrateNext]]
- [[proto/cmd.Compass.CalibrateCencel]]


### Preconditions

- Compass must be started


### Implementation Notes

No parameters required. Simple button invocation via calibrateShortStart() function.



