---
id: cmd.HeatCamera.Stop
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Stop

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Stops the thermal camera subsystem and deactivates thermal imaging capture. This parameterless lifecycle command shuts down the heat camera and its processing pipeline, returning to an inactive state.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Stops the thermal camera subsystem


### Related State

- [[proto/ser.JonGuiDataCameraHeat#is_started]]


### Related Commands

- [[proto/cmd.HeatCamera.Start]]





