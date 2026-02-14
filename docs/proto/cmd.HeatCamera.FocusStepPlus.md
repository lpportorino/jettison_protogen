---
id: cmd.HeatCamera.FocusStepPlus
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusStepPlus

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Increments the thermal camera focus by one discrete step, bringing the focus point closer to the camera. This parameterless fire-and-forget stepper command provides single-step manual focus adjustment in the UI focus control panel.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Increments thermal camera focus by one discrete step (closer)



### Related State

- [[proto/ser.JonGuiDataCameraHeat]] - Contains focus_mode field indicating manual vs automatic focus


### Related Commands

- [[proto/cmd.HeatCamera.FocusStepMinus]]
- [[proto/cmd.HeatCamera.FocusIn]]
- [[proto/cmd.HeatCamera.FocusOut]]


### Preconditions

- Thermal camera must be started




