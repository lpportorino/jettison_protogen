---
id: cmd.HeatCamera.RefreshFxMode
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# RefreshFxMode

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Triggers a refresh/reapplication of the current visual effects (FX) mode on the thermal camera. This parameterless fire-and-forget command reinitializes the current FX processing without changing modes, useful after parameter changes or to ensure proper mode activation.

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

- [[proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/cmd.HeatCamera.SetFxMode]]
- [[proto/proto/proto/cmd.HeatCamera.NextFxMode]]
- [[proto/proto/proto/cmd.HeatCamera.PrevFxMode]]



### Implementation Notes

Reinitializes current visual effects processing



