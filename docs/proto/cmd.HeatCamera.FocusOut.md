---
id: cmd.HeatCamera.FocusOut
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusOut

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Commands the thermal camera to continuously move focus farther away (toward infinity) while held. This parameterless trigger uses a press-accelerating UI pattern for continuous focus adjustment until released or FocusStop is called.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :press-accelerating
- **Feedback:** :fire-and-forget


### Purpose

Moves thermal camera focus continuously farther (hold to continue)



### Related State

- [[proto/ser.JonGuiDataCameraHeat]] - Contains focus_mode field indicating manual vs automatic focus


### Related Commands

- [[proto/cmd.HeatCamera.FocusIn]]
- [[proto/cmd.HeatCamera.FocusStop]]
- [[proto/cmd.HeatCamera.SetAutoFocus]]


### Preconditions

- Thermal camera must be started
- Auto-focus must be disabled (manual focus mode)




