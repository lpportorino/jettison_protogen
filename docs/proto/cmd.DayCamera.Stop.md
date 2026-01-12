---
id: cmd.DayCamera.Stop
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Stop

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Stops day camera operation and releases associated hardware resources. This parameterless lifecycle command terminates the active day camera stream that was started with the Start command, returning the camera to an inactive state.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Stop day camera operation and release resources


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.Start]]





