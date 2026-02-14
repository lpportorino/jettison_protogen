---
id: cmd.HeatCamera.ZoomStop
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ZoomStop

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Stops the thermal camera zoom motion in progress, sent when the zoom button is released after a zoom in or out command. Used in press-release input patterns for analog zoom control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops continuous zoom movement on heat camera


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.ZoomIn]]
- [[proto/cmd.HeatCamera.ZoomOut]]


### Preconditions

- Heat camera must be started
- A continuous zoom operation (ZoomIn or ZoomOut) should be in progress


### Notes

- Sent automatically when user releases zoom button (mouseup/touchend event)
- Also sent on presscancel events (e.g., finger slides off button)
- Frontend function: `heatCameraZoomStop()` in `cmdHeatCamera.ts`
- Control panel button "HCamZoomStop" triggers this directly on click
- Part of press-hold-release pattern: ZoomIn/ZoomOut on press, ZoomStop on release


### Implementation Notes

Completes the continuous zoom command cycle. In the frontend, this is used by:
- `jonFocusUi.ts`: Bound to `@pressend` and `@presscancel` events on thermal zoom buttons via `handleThermalZoomStop()`
- `ctl_app.js`: Bound to `mouseup` events on HCamZoomIn/HCamZoomOut buttons
- Dedicated "Zoom Stop" button (HCamZoomStop) for manual stop if needed



