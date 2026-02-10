---
id: cmd.HeatCamera.ZoomIn
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ZoomIn

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Initiates continuous zoom-in motion on the thermal camera. This parameterless command starts increasing magnification and requires a ZoomStop command to halt the operation, using a press-accelerating UI pattern for smooth zoom control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :press-accelerating
- **Feedback:** :fire-and-forget


### Purpose

Start zooming heat camera in (continuous motion)


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.ZoomOut]]
- [[proto/cmd.HeatCamera.ZoomStop]]



### Implementation Notes

Continuous zoom command, requires ZoomStop to halt



