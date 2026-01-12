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

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Halts all thermal camera motor movements (zoom and focus)


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.ZoomStop]]
- [[proto/proto/cmd.HeatCamera.FocusStop]]


### Preconditions



### Implementation Notes

Emergency stop for camera movements. No parameters.



