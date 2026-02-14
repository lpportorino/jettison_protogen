---
id: cmd.HeatCamera.FocusStop
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusStop

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Stops continuous thermal camera focus movement initiated by FocusIn or FocusOut commands. This parameterless fire-and-forget command halts ongoing focus motor operation when the user releases the focus control button.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops continuous thermal camera focus movement



### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.FocusIn]]
- [[proto/cmd.HeatCamera.FocusOut]]


### Preconditions

- Thermal camera must be started
- Focus movement in progress


### Implementation Notes

Empty message - trigger only. Sent on button release after FocusIn/FocusOut, or via dedicated stop button (hotkey 's' in Heat Focus overlay).

