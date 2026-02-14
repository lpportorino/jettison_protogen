---
id: cmd.DayCamera.ResetZoom
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# ResetZoom

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Resets the day camera's optical zoom to its default position (typically 1x or minimum zoom). Triggered via an action button in the UI zoom control panel with a 2-second pending timeout while waiting for confirmation. The UI monitors `zoomPos` in state to detect completion before the timeout expires.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Resets day camera zoom to default position (typically 1x or minimum zoom). Used as a quick way to return to wide-angle view.


### Related State

- [[proto/ser.JonGuiDataCameraDay]] - provides `zoomPos` field to confirm reset completion


### Related Commands

- [[proto/cmd.DayCamera.Zoom]] - parent wrapper message
- [[proto/cmd.DayCamera.Halt]] - stops zoom motor movement
- [[proto/cmd.DayCamera.Offset]] - incremental zoom adjustment
- [[proto/cmd.DayCamera.SaveToTable]] - saves current zoom position
- [[proto/cmd.DayCamera.SetZoomTableValue]] - sets zoom to table entry


### Preconditions

- Day camera must be started




