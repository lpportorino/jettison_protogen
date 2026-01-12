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

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Start long compass calibration procedure



### Related Commands

- [[proto/proto/proto/cmd.Compass.Next]]
- [[proto/proto/proto/cmd.Compass.CalibrateCencel]]



### Implementation Notes

Multi-step calibration process requiring user to rotate device



