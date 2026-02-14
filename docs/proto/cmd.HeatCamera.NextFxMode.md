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

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.SetFxMode]]
- [[proto/cmd.HeatCamera.PrevFxMode]]
- [[proto/cmd.HeatCamera.RefreshFxMode]]


### Implementation Notes

Cycles through image enhancement modes. The frontend guards against sending this command when already at MODE_HEAT_F (the last mode), preventing wrap-around at the UI level. Available modes cycle through: DEFAULT (0) -> A (1) -> B (2) -> C (3) -> D (4) -> E (5) -> F (6). Accessible via hotkey 'n' in the Heat FX Mode submenu.



