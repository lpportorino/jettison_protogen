---
id: cmd.HeatCamera.Calibrate
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Calibrate

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Trigger thermal camera calibration cycle


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]




### Implementation Notes

Performs NUC (Non-Uniformity Correction) calibration



