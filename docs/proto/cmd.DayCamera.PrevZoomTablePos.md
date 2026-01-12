---
id: cmd.DayCamera.PrevZoomTablePos
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# PrevZoomTablePos

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Decrements the day camera optical zoom to the previous position in the zoom table. Counterpart to NextZoomTablePos, this parameterless command steps backward through predefined zoom levels. Commonly triggered via hotkey commands and mouse wheel interactions for quick zoom-out operations.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Decrements day camera zoom to previous table position


### Related State

- [[proto/proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/proto/cmd.DayCamera.NextZoomTablePos]]
- [[proto/proto/proto/cmd.DayCamera.SetZoomTableValue]]


### Preconditions

- Camera must be started


### Implementation Notes

No parameters. Simple decrement operation. Used in hotkey commands and mouse wheel interactions.



