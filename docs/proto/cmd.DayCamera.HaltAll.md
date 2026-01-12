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



### Related Commands

- [[proto/proto/proto/proto/proto/cmd.DayCamera.Focus]]
- [[proto/proto/proto/proto/proto/cmd.DayCamera.Zoom]]


### Preconditions

- Day camera must be started


### Implementation Notes

Emergency stop for all camera actuators



