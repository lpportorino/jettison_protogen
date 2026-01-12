---
id: cmd.DayCamera.NextZoomTablePos
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# NextZoomTablePos

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Steps to next predefined optical zoom position


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.Zoom]]
- [[proto/proto/cmd.DayCamera.PrevZoomTablePos]]



### Implementation Notes

Cycles through zoom presets



