---
id: cmd.HeatCamera.PrevZoomTablePos
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# PrevZoomTablePos

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Moves the thermal camera to the previous position in the zoom lookup table, stepping backward through saved zoom presets. This parameterless command complements NextZoomTablePos for bidirectional zoom preset navigation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Move to previous position in heat camera zoom lookup table


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.NextZoomTablePos]]
- [[proto/cmd.HeatCamera.SetZoomTableValue]]
- [[proto/cmd.HeatCamera.ResetZoom]]
- [[proto/cmd.HeatCamera.SaveToTable]]


### Preconditions

- Heat camera must be started




