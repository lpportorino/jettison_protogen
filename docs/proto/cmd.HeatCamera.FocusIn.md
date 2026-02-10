---
id: cmd.HeatCamera.FocusIn
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusIn

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Commands the thermal camera to continuously focus toward near distances while held. This parameterless trigger is used with button press/release or gamepad input as part of the focus control system alongside FocusOut and FocusStop commands.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :press-accelerating
- **Feedback:** :fire-and-forget


### Purpose

Continuously focus toward near (while held)


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.FocusOut]]
- [[proto/cmd.HeatCamera.FocusStop]]



### Implementation Notes

Empty message - trigger only. Used with button press/release or gamepad



