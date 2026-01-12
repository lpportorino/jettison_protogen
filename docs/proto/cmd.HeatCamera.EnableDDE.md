---
id: cmd.HeatCamera.EnableDDE
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# EnableDDE

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Enables Digital Detail Enhancement (DDE) on the thermal camera to enhance image detail visibility. This parameterless command activates additional image processing that sharpens edges and improves fine feature visibility in the thermal image.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Enables Digital Detail Enhancement on thermal camera


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/proto/proto/cmd.HeatCamera.DisableDDE]]
- [[proto/proto/proto/proto/proto/cmd.HeatCamera.SetDDELevel]]



### Implementation Notes

DDE enhances image detail visibility



