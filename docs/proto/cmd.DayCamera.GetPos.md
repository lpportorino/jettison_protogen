---
id: cmd.DayCamera.GetPos
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# GetPos

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Requests current zoom/focus position from day camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]



### Preconditions

- Day camera started


### Implementation Notes

Diagnostic query command, response updates state



