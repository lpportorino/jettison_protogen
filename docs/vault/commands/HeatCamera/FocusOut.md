---
id: cmd.HeatCamera.FocusOut
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusOut

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Moves thermal camera focus outward (away from camera). This is a continuous motion command that moves focus until a stop command is sent.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget

### Purpose

Moves the thermal camera focus outward (away from the camera body). Used for focusing on distant objects.

### Related State

- [[ser.JonGuiDataCameraHeat]]

### Related Commands

- [[cmd.HeatCamera.FocusIn]]
- [[cmd.HeatCamera.FocusStop]]
- [[cmd.HeatCamera.FocusStepPlus]]
- [[cmd.HeatCamera.FocusStepMinus]]

### Implementation Notes

This is a continuous motion command. The focus motor will continue moving outward until a FocusStop command is sent or the motor reaches its limit. For press-and-hold behavior, send FocusOut on button press and FocusStop on button release.



