---
id: cmd.HeatCamera.NextZoomTablePos
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# NextZoomTablePos

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Moves the thermal camera to the next preset zoom position in the zoom lookup table. This parameterless trigger command is nested within the Zoom submessage and advances through predefined optical zoom levels for quick magnification changes.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :pending-timeout


### Purpose

Move to next preset zoom position in lookup table


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.Zoom]]
- [[proto/cmd.HeatCamera.PrevZoomTablePos]]
- [[proto/cmd.HeatCamera.SetZoomTableValue]]
- [[proto/cmd.HeatCamera.SaveToTable]]
- [[proto/cmd.DayCamera.NextZoomTablePos]]



### Implementation Notes

Empty message - trigger only. Part of Zoom submessage.

In the frontend, this command is triggered by:
- Pinch-to-zoom gestures on the video stream (zoom in direction)
- Mouse wheel scrolling on the video canvas
- Keyboard hotkeys for zoom control
- Direct button presses in the zoom palette UI

When camera sync mode is enabled (`isSynced`), both `heatCameraNextZoomTablePos()` and `dayCameraNextZoomTablePos()` are called together, keeping both cameras at the same zoom table position.



