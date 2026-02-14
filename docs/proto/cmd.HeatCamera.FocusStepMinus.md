---
id: cmd.HeatCamera.FocusStepMinus
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusStepMinus

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Decrements the thermal camera focus by one discrete step, moving the focus point farther away. This parameterless fire-and-forget command is used in the UI focus control panel for precise single-step focus adjustments.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Decrements thermal camera focus by one discrete step (farther)



### Related State

- [[proto/ser.JonGuiDataCameraHeat]] - Contains focus_mode field indicating manual vs automatic focus


### Related Commands

- [[proto/cmd.HeatCamera.FocusStepPlus]]
- [[proto/cmd.HeatCamera.FocusOut]]
- [[proto/cmd.HeatCamera.FocusIn]]


### Preconditions

- Thermal camera must be started




