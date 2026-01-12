---
id: cmd.HeatCamera.Photo
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Photo

**Source:** `jon_shared_cmd_heat_camera.proto`

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

Captures a photo from the thermal camera


### Related State

- [[proto/proto/ser.JonGuiDataLrf]]




### Implementation Notes

Button shows pending state, becomes active when LRF target ID changes (photo captured)



