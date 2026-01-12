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



### Related Commands

- [[proto/proto/proto/proto/proto/cmd.HeatCamera.FocusStepMinus]]
- [[proto/proto/proto/proto/proto/cmd.HeatCamera.FocusIn]]


### Preconditions

- Thermal camera must be started




