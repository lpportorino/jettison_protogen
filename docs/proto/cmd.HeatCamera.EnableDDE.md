---
id: cmd.HeatCamera.EnableDDE
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# EnableDDE

**Source:** `jon_shared_cmd_heat_camera.proto`

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

Enables Digital Detail Enhancement on thermal camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.DisableDDE]]
- [[proto/proto/cmd.HeatCamera.SetDDELevel]]



### Implementation Notes

DDE enhances image detail visibility



