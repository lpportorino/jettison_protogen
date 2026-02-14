---
id: cmd.HeatCamera.Photo
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Photo

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Triggers the thermal camera to capture a still photo from the current video feed. This parameterless command shows pending state in the UI button until capture completes, which is confirmed when the LRF target ID changes.

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

- [[proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/cmd.DayCamera.Photo]]


### Implementation Notes

Button shows pending state, becomes active when LRF target ID changes (photo captured)



