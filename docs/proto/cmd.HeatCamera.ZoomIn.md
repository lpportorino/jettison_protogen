---
id: cmd.HeatCamera.ZoomIn
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ZoomIn

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Initiates continuous zoom-in motion on the thermal camera. This parameterless command starts increasing magnification and requires a ZoomStop command to halt the operation. Used in the frontend for button press-and-hold interactions where zoom continues while the button is pressed, then stops when released via ZoomStop. For discrete step-based zooming (e.g., mouse wheel or tap), use the Zoom.NextZoomTablePos command instead.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :press-accelerating
- **Feedback:** :fire-and-forget


### Purpose

Start zooming heat camera in (continuous motion)


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.ZoomOut]] - Continuous zoom out (requires ZoomStop)
- [[proto/cmd.HeatCamera.ZoomStop]] - Stops continuous zoom motion
- [[proto/cmd.HeatCamera.Zoom.NextZoomTablePos]] - Discrete step zoom in (preferred for mouse wheel/gestures)
- [[proto/cmd.HeatCamera.Zoom.PrevZoomTablePos]] - Discrete step zoom out



### Implementation Notes

Continuous zoom command, requires ZoomStop to halt. In the frontend, this is primarily used by the control panel's zoom buttons (e.g., HCamZoomIn) with mousedown/mouseup event handlers that call ZoomIn on press and ZoomStop on release. The pinch-to-zoom and mouse wheel gestures use the discrete NextZoomTablePos/PrevZoomTablePos commands instead for better control.



