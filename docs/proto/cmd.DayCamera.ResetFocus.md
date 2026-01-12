---
id: cmd.DayCamera.ResetFocus
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# ResetFocus

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Resets the day camera's focus to its default or home position. This parameterless trigger command provides a one-click way to return focus to a known baseline state, exposed as a Reset action button in the UI with pending-timeout feedback.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Reset focus to default/home position


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/proto/proto/proto/cmd.DayCamera.Focus]]



### Implementation Notes

Empty message - trigger only



