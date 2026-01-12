---
id: cmd.HeatCamera.NextZoomTablePos
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# NextZoomTablePos

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Moves the thermal camera to the next preset zoom position in the zoom lookup table. This parameterless trigger command is nested within the Zoom submessage and advances through predefined optical zoom levels for quick magnification changes.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :pending-timeout


### Purpose

Move to next preset zoom position in lookup table


### Related State

- [[proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/cmd.HeatCamera.Zoom]]
- [[proto/proto/proto/cmd.HeatCamera.PrevZoomTablePos]]



### Implementation Notes

Empty message - trigger only. Part of Zoom submessage



