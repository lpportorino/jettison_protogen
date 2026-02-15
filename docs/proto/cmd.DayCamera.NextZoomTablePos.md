---
id: cmd.DayCamera.NextZoomTablePos
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# NextZoomTablePos

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Advances the day camera to the next predefined optical zoom position in the zoom table. The zoom table contains preset magnification levels (e.g., 1x, 2x, 4x, 10x) allowing quick jumps between commonly-used zoom levels without continuous adjustment.

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

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.Zoom]]
- [[proto/cmd.DayCamera.PrevZoomTablePos]]



### Implementation Notes

Cycles through zoom presets



