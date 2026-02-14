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
- **Feedback:** :pending-timeout <!-- Physical zoom motor movement requires timeout feedback -->


### Purpose

Decrements day camera zoom to previous table position


### Related State

- [[proto/ser.JonGuiDataCameraDay]] - `zoomTablePos`, `zoomTablePosMax`


### Related Commands

- [[proto/cmd.DayCamera.NextZoomTablePos]]
- [[proto/cmd.DayCamera.SetZoomTableValue]]
- [[proto/cmd.DayCamera.Zoom.SaveToTable]]


### Preconditions

- Camera must be started
- Current `zoomTablePos > 0` (frontend enforces this check before sending)


### Implementation Notes

No parameters. Simple decrement operation. Used in hotkey commands (`prevZoomPosition`) and mouse wheel/pinch-out gestures (`handleZoomOut`). When zoom sync is enabled, both day and heat cameras step to previous position together.



