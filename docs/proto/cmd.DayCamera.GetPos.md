---
id: cmd.DayCamera.GetPos
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# GetPos

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Requests the current zoom and focus position values from the day camera, triggering a state update with the latest position data. Useful for synchronizing UI state or debugging position discrepancies. Response updates focus_pos, zoom_pos, iris_pos in JonGuiDataCameraDay.

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

- [[proto/proto/proto/ser.JonGuiDataCameraDay]]



### Preconditions

- Day camera started


### Implementation Notes

Diagnostic query command, response updates state



