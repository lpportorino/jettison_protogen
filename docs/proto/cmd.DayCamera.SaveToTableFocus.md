---
id: cmd.DayCamera.SaveToTableFocus
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SaveToTableFocus

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Saves the current camera focus position to a lookup table for quick recall. This parameterless trigger command enables the camera to store and later retrieve predefined focus positions, commonly used via a save-focus action button in the day camera controls.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Save current focus position to lookup table


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.DayCamera.Focus]]



### Implementation Notes

Empty message - trigger only



