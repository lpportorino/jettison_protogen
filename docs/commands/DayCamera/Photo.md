---
id: cmd.DayCamera.Photo
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Photo

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Captures a still image from the day camera. This is a fire-and-forget command that triggers the photo capture process.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :dual-feedback


### Purpose

Initiates photo capture from the visible spectrum camera. Success is indicated by a change in the LRF target ID in the state.


### Related State

- [[ser.JonGuiDataCameraDay]]
- [[ser.JonGuiDataLrf]]




### Implementation Notes

Show pending state during capture. On success (detected via LRF target ID change), flash the button active state for 500ms to provide user feedback. No direct confirmation message is sent - success must be inferred from state change.



