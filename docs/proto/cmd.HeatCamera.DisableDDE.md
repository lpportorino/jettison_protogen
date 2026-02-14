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
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Disables DDE (Digital Detail Enhancement) on thermal camera, turning off edge detail enhancement in the thermal image output.


### Related State

- [[proto/ser.JonGuiDataCameraHeat]] - `ddeEnabled` and `ddeLevel` fields reflect DDE status


### Related Commands

- [[proto/cmd.HeatCamera.EnableDDE]] - Enables DDE processing
- [[proto/cmd.HeatCamera.SetDDELevel]] - Sets DDE intensity (0-255)
- [[proto/cmd.HeatCamera.ShiftDDE]] - Incrementally adjusts DDE level


### Preconditions

- Heat camera must be started
- DDE must currently be enabled (command is idempotent but typically called when toggling off)


### UI Integration

- Toggle button in `jon-dde-ui` component under "Digital Detail Enhancement" card
- Keyboard shortcut: `t` key in transient DDE overlay (`jon-transient-dde-overlay`)
- Button shows pending state during the 2s timeout while awaiting state confirmation




