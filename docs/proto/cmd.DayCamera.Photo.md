---
id: cmd.DayCamera.Photo
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Photo

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataLrf]]




### Implementation Notes

Button shows pending state, becomes active when LRF target ID changes (photo captured)



