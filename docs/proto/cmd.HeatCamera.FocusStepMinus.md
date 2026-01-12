---
id: cmd.HeatCamera.FocusStepMinus
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusStepMinus

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Decrements thermal camera focus by one discrete step (farther)



### Related Commands

- [[proto/proto/cmd.HeatCamera.FocusStepPlus]]
- [[proto/proto/cmd.HeatCamera.FocusOut]]


### Preconditions

- Thermal camera must be started




