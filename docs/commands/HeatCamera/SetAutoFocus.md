---
id: cmd.HeatCamera.SetAutoFocus
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetAutoFocus

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Enables or disables automatic focus for the thermal camera. When enabled, the camera continuously adjusts focus to maintain sharpness.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | bool | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Controls whether the thermal camera automatically maintains focus. Disabling allows manual focus control for specific scenarios.


### Related State

- [[ser.JonGuiDataCameraHeat]]


### Related Commands

- [[cmd.HeatCamera.FocusStepPlus]]
- [[cmd.HeatCamera.FocusStepMinus]]
- [[cmd.HeatCamera.FocusIn]]
- [[cmd.HeatCamera.FocusOut]]



### Implementation Notes

When auto-focus is enabled, manual focus commands are ignored. The UI should visually indicate auto mode and disable manual focus controls.



## Field Notes


### value (#1)

True to enable auto-focus, false for manual control.


#### Metadata

- **Semantic Type:** :toggle-state



