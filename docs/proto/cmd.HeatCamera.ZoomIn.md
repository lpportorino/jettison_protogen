---
id: cmd.HeatCamera.ZoomIn
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ZoomIn

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.ZoomOut]]
- [[proto/proto/cmd.HeatCamera.ZoomStop]]



### Implementation Notes

Continuous zoom command, requires ZoomStop to halt



