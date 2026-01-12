---
id: cmd.HeatCamera.ZoomStop
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ZoomStop

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops continuous zoom movement on heat camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.ZoomIn]]
- [[proto/proto/cmd.HeatCamera.ZoomOut]]


### Preconditions

- Heat camera started


### Implementation Notes

Used with continuous zoom in/out commands



