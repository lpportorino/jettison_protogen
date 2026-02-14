---
id: cmd.HeatCamera.Halt
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Halt

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Emergency stop command that halts all thermal camera motor movements including both zoom and focus operations. This parameterless command provides an immediate safety mechanism to stop any ongoing actuator movement.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Halts all thermal camera motor movements (zoom and focus)


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.ZoomStop]]
- [[proto/cmd.HeatCamera.FocusStop]]
- [[proto/cmd.HeatCamera.ZoomIn]]
- [[proto/cmd.HeatCamera.ZoomOut]]
- [[proto/cmd.HeatCamera.FocusIn]]
- [[proto/cmd.HeatCamera.FocusOut]]


### Preconditions

- Thermal camera must be started


### Implementation Notes

Emergency stop for camera movements. No parameters. This is a standalone message that stops all motor activity, unlike ZoomStop/FocusStop which target specific subsystems.



