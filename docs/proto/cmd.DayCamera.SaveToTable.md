---
id: cmd.DayCamera.SaveToTable
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SaveToTable

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Saves current day camera zoom position to zoom table


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.ResetZoom]]
- [[proto/proto/cmd.DayCamera.SetZoomTableValue]]


### Preconditions

- Day camera must be started
- Valid zoom position




