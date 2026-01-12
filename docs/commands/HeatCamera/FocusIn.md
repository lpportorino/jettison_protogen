---
id: cmd.HeatCamera.FocusIn
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusIn

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Moves thermal camera focus inward (towards camera). This is a continuous motion command that moves focus until a stop command is sent.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Moves the thermal camera focus inward (towards the camera body). Used for focusing on closer objects.


### Related State

- [[ser.JonGuiDataCameraHeat]]


### Related Commands

- [[cmd.HeatCamera.FocusOut]]
- [[cmd.HeatCamera.FocusStop]]
- [[cmd.HeatCamera.FocusStepPlus]]
- [[cmd.HeatCamera.FocusStepMinus]]



### Implementation Notes

This is a continuous motion command. The focus motor will continue moving inward until a FocusStop command is sent or the motor reaches its limit. For press-and-hold behavior, send FocusIn on button press and FocusStop on button release.



