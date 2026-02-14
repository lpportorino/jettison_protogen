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

- **Category:** :diagnostic <!-- Sensor calibration is a diagnostic/maintenance operation -->
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget <!-- Frontend uses simple button click without pending state -->


### Purpose

Trigger thermal camera NUC (Non-Uniformity Correction) calibration cycle to compensate for pixel-to-pixel sensor variations and improve image accuracy.


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.Start]] - Camera must be started before calibration
- [[proto/cmd.HeatCamera.Stop]]


### Implementation Notes

- Performs NUC (Non-Uniformity Correction) calibration
- Exposed in UI via "Calibrate" button in the thermal camera sensor controls palette (`jonHeatCalibratePalette`)
- Also available as hotkey command (`calibrateHeatCamera`)
- Parameterless fire-and-forget command - no confirmation or status feedback in current UI



