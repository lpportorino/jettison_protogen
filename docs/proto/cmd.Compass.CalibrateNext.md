---
id: cmd.Compass.CalibrateNext
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# CalibrateNext

**Source:** `jon_shared_cmd_compass.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCompassCalibration]]


### Related Commands

- [[proto/proto/cmd.Compass.CalibrateStartLong]]
- [[proto/proto/cmd.Compass.CalibrateStartShort]]
- [[proto/proto/cmd.Compass.CalibrateCencel]]


### Preconditions

- Calibration must be in progress




