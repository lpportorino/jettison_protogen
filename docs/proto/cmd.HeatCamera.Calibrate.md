---
id: cmd.HeatCamera.Calibrate
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Calibrate

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Triggers a Non-Uniformity Correction (NUC) calibration cycle on the thermal camera to improve image accuracy. This parameterless fire-and-forget command adjusts the sensor to compensate for pixel-to-pixel variations and is typically invoked via a UI calibration button.

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



