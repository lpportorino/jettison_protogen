---
id: cmd.DayCamera.SetDigitalZoomLevel
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetDigitalZoomLevel

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Controls the digital zoom magnification level for the day camera. Accepts a value representing zoom magnification (1x or higher) and operates as a fire-and-forget command with slider UI. Can be synchronized with heat camera digital zoom via a UI sync toggle for coordinated dual-camera operation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Controls digital zoom magnification level for day camera


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.DayCamera.SetZoomTableValue]]
- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.SetDigitalZoomLevel]]



### Implementation Notes

Can be synchronized with heat camera digital zoom via UI sync toggle



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** x
- **Precision:** 2
- **Display Format:** `{value}x`



