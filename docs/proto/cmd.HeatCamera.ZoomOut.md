---
id: cmd.HeatCamera.ZoomOut
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ZoomOut

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Instructs the thermal camera to decrease its zoom level, typically triggered by gamepad button presses or UI controls to zoom out and view a wider field of view from the thermal imaging sensor.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :press-accelerating
- **Feedback:** :optimistic-visual


### Purpose

Decrease heat camera zoom level (zoom out/wider field of view)


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.ZoomIn]]
- [[proto/cmd.HeatCamera.ZoomStop]]
- [[proto/cmd.HeatCamera.ResetZoom]]


### Preconditions

- Heat camera must be started




