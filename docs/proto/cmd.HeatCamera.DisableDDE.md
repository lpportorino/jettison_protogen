---
id: cmd.HeatCamera.DisableDDE
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# DisableDDE

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Disables Digital Detail Enhancement (DDE) on the thermal camera. This parameterless toggle command turns off the image processing that enhances edge detail and fine features, returning to standard thermal image output.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Disables DDE (Digital Detail Enhancement) on thermal camera


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.EnableDDE]]
- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.SetDDELevel]]


### Preconditions

- Heat camera must be started




