---
id: cmd.DayCamera.Photo
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Photo

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Triggers a photo capture from the day camera. This parameterless command captures a still image from the current video feed. The UI button shows pending state until capture completes, which is indicated by a change in the LRF target ID.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Captures a photo from the day camera


### Related State

- [[proto/ser.JonGuiDataLrf]]




### Implementation Notes

Button shows pending state, becomes active when LRF target ID changes (photo captured)



