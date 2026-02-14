---
id: cmd.HeatCamera.SetCalibMode
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetCalibMode

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the calibration mode for the thermal camera, controlling how the sensor performs Non-Uniformity Correction (NUC). This parameterless command likely toggles between manual and automatic NUC calibration modes. When automatic calibration is enabled, the camera performs periodic NUC corrections without user intervention; in manual mode, the user must explicitly trigger calibration via the [[proto/cmd.HeatCamera.Calibrate]] command.

<!-- NEEDS_REVIEW: The message has no fields in the proto definition. It may be a toggle (switching between manual/auto modes), or the actual mode value may be passed through a different mechanism not yet implemented. -->

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings <!-- Camera configuration setting -->
- **UI Pattern:** :toggle <!-- NEEDS_REVIEW: Likely a toggle between manual/auto modes, but no fields suggest enum-picker may be premature -->
- **Feedback:** :fire-and-forget <!-- NEEDS_REVIEW: No frontend usage found to confirm feedback pattern -->


### Purpose

Configure the NUC (Non-Uniformity Correction) calibration behavior for the thermal camera, switching between automatic periodic calibration and manual user-triggered calibration modes.


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.Calibrate]] - Triggers manual NUC calibration cycle
- [[proto/cmd.HeatCamera.Start]] - Camera must be started before setting calibration mode
- [[proto/cmd.HeatCamera.Stop]]


### Implementation Notes

- Defined in proto as an empty message (no fields) at field number 31 in HeatCamera.Root
- No frontend UI implementation found - command is not currently exposed in the thermal camera control palette
- Distinct from [[proto/cmd.HeatCamera.Calibrate]] which triggers an immediate manual NUC cycle
- May be a placeholder for future implementation or require backend-only usage

<!-- NEEDS_REVIEW: No frontend usage found. The proto defines this as an empty message, suggesting either:
     1. It's a stateless toggle command (sends empty message to flip between modes)
     2. The actual mode selection mechanism is not yet implemented
     3. It may be used only by backend systems or factory calibration tools -->



