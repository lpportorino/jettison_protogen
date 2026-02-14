---
id: cmd.DayCamera.HaltAll
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# HaltAll

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Emergency stop command that immediately halts all day camera actuator movements (both zoom and focus motors). This parameterless command provides a safety mechanism to stop any ongoing lens movement operations instantly.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops all day camera movements (zoom and focus)



### Related State

- [[proto/ser.JonGuiDataCameraDay]] - `zoomPos` and `focusPos` fields stop changing when halt is issued


### Related Commands

- [[proto/cmd.DayCamera.Halt]] - Sub-command to halt individual zoom/focus motor (used within Focus/Zoom composite commands)
- [[proto/cmd.DayCamera.Focus]] - Composite focus command (contains halt sub-command)
- [[proto/cmd.DayCamera.Zoom]] - Composite zoom command (contains halt sub-command)


### Preconditions

- Day camera must be started


### Implementation Notes

Emergency stop for all camera actuators



