---
id: cmd.HeatCamera.NextZoomTablePos
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# NextZoomTablePos

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.Zoom]]
- [[proto/proto/cmd.HeatCamera.PrevZoomTablePos]]



### Implementation Notes

Empty message - trigger only. Part of Zoom submessage



