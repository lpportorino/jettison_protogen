---
id: cmd.HeatCamera.SetAutoFocus
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetAutoFocus

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | bool | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Enables or disables auto-focus for thermal camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.CV.SetAutoFocus]]


### Preconditions

- Camera must be started


### Implementation Notes

Used in jonFocusUi component. Part of focus control system with manual steppers and auto mode toggle.



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :raw



