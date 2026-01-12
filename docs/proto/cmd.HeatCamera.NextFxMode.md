---
id: cmd.HeatCamera.NextFxMode
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# NextFxMode

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Cycles to the next FX enhancement mode on the thermal camera, advancing through available image enhancement filters in sequence. This parameterless command wraps around to the first mode after reaching the last one.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Cycles to next FX enhancement mode


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/proto/proto/cmd.HeatCamera.SetFxMode]]
- [[proto/proto/proto/proto/proto/cmd.HeatCamera.PrevFxMode]]



### Implementation Notes

Cycles through image enhancement modes



