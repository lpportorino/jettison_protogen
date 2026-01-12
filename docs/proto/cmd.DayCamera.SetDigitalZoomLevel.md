---
id: cmd.DayCamera.SetDigitalZoomLevel
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetDigitalZoomLevel

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.SetZoomTableValue]]
- [[proto/proto/cmd.HeatCamera.SetDigitalZoomLevel]]



### Implementation Notes

Can be synchronized with heat camera digital zoom via UI sync toggle



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** x
- **Precision:** 2
- **Display Format:** `{value}x`



