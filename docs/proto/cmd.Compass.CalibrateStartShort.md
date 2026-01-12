---
id: cmd.Compass.CalibrateStartShort
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# CalibrateStartShort

**Source:** `jon_shared_cmd_compass.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/proto/cmd.Compass.CalibrateStartLong]]
- [[proto/proto/cmd.Compass.CalibrateNext]]
- [[proto/proto/cmd.Compass.CalibrateCencel]]


### Preconditions

- Compass must be started


### Implementation Notes

No parameters required. Simple button invocation via calibrateShortStart() function.



