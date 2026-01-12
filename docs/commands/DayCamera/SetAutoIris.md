---
id: cmd.DayCamera.SetAutoIris
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetAutoIris

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Enables or disables automatic iris control. When enabled, the camera automatically adjusts the iris aperture to maintain optimal exposure.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | bool | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Controls whether the camera automatically adjusts iris based on scene brightness. Disabling auto-iris allows manual control via [[cmd.DayCamera.SetIris]].


### Related State

- [[ser.JonGuiDataCameraDay]]


### Related Commands

- [[cmd.DayCamera.SetIris]]



### Implementation Notes

When auto-iris is enabled, [[cmd.DayCamera.SetIris]] commands are ignored. The UI should visually indicate auto mode is active and disable manual iris controls.



## Field Notes


### value (#1)

True to enable auto-iris, false for manual control.


#### Metadata

- **Semantic Type:** :toggle-state



