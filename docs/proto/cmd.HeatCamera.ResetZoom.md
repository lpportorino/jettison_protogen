---
id: cmd.HeatCamera.ResetZoom
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ResetZoom

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Resets the thermal camera optical zoom position to its default minimum value. This parameterless actuator command returns the zoom to 1x or minimum magnification, triggered via an action button with pending-timeout feedback.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Reset heat camera zoom position to default/minimum


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.ZoomIn]]
- [[proto/proto/cmd.HeatCamera.ZoomOut]]
- [[proto/proto/cmd.HeatCamera.SaveToTable]]


### Preconditions

- Heat camera must be started




