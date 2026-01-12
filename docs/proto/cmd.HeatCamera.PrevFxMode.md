---
id: cmd.HeatCamera.PrevFxMode
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# PrevFxMode

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Cycles to previous FX mode for thermal camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.NextFxMode]]
- [[proto/proto/cmd.HeatCamera.SetFxMode]]


### Preconditions

- Heat camera must be started




