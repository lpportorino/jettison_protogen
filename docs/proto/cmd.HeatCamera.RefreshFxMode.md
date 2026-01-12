---
id: cmd.HeatCamera.RefreshFxMode
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# RefreshFxMode

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

Refreshes/reapplies the current FX mode for thermal camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.SetFxMode]]
- [[proto/proto/cmd.HeatCamera.NextFxMode]]
- [[proto/proto/cmd.HeatCamera.PrevFxMode]]



### Implementation Notes

Reinitializes current visual effects processing



