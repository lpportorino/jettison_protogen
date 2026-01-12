---
id: cmd.HeatCamera.PrevZoomTablePos
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# PrevZoomTablePos

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.NextZoomTablePos]]
- [[proto/proto/cmd.HeatCamera.ResetZoom]]
- [[proto/proto/cmd.HeatCamera.SaveToTable]]


### Preconditions

- Heat camera must be started




