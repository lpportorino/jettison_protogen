---
id: cmd.HeatCamera.PrevFxMode
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# PrevFxMode

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Cycles to the previous FX (effects) mode on the thermal camera, navigating backward through available thermal imaging enhancement filters. Paired with NextFxMode and SetFxMode for complete FX mode navigation control.

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

- [[proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/cmd.HeatCamera.NextFxMode]]
- [[proto/proto/proto/cmd.HeatCamera.SetFxMode]]


### Preconditions

- Heat camera must be started




