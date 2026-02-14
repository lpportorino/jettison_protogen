---
id: cmd.HeatCamera.SetAutoFocus
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetAutoFocus

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Enables or disables automatic focus for the thermal camera. When enabled (value=true), the camera automatically adjusts focus based on the scene; when disabled, manual focus controls (FocusIn, FocusOut, step commands) become active.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Enables or disables auto-focus for thermal camera. When auto-focus is enabled, the camera automatically adjusts focus based on the scene. When disabled, manual focus controls become active.


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.CV.SetAutoFocus]]
- [[proto/cmd.HeatCamera.FocusIn]]
- [[proto/cmd.HeatCamera.FocusOut]]
- [[proto/cmd.HeatCamera.FocusStepPlus]]
- [[proto/cmd.HeatCamera.FocusStepMinus]]
- [[proto/cmd.HeatCamera.FocusStop]]
- [[proto/cmd.HeatCamera.FocusROI]]


### Preconditions

- Camera must be started


### Implementation Notes

Used in jonFocusUi component ("TF" panel for thermal focus). The UI provides an "AF" button that triggers `heatCameraSetAutoFocusOn()` to enable auto-focus. Manual focus step buttons (+/-) provide single-step adjustments when auto-focus is disabled.



## Field Notes


### value (#1)

Enable/disable auto-focus state. When `true`, the thermal camera automatically adjusts focus. When `false`, manual focus controls (FocusIn, FocusOut, FocusStepPlus, FocusStepMinus) become active.


#### Metadata

- **Semantic Type:** :toggle-state



