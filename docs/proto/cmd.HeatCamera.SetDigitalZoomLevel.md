---
id: cmd.HeatCamera.SetDigitalZoomLevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetDigitalZoomLevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the digital zoom magnification level for the thermal camera to a specified value (minimum 1x). Uses a slider UI interface and can be synchronized with day camera digital zoom via a UI sync toggle for coordinated dual-camera operation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Controls digital zoom magnification level for thermal camera


### Related State

- [[proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/cmd.HeatCamera.SetZoomTableValue]]
- [[proto/proto/proto/cmd.DayCamera.SetDigitalZoomLevel]]



### Implementation Notes

Can be synchronized with day camera digital zoom via UI sync toggle



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** x
- **Precision:** 2
- **Display Format:** `{value}x`



