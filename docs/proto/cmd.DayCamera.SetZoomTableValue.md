---
id: cmd.DayCamera.SetZoomTableValue
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetZoomTableValue

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the day camera to a specific zoom table position by index value. This command allows direct selection of predefined optical zoom levels in the camera's zoom table, providing quick access to commonly-used magnification presets.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | int32 | >= 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Sets day camera zoom to specific table position


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.NextZoomTablePos]]
- [[proto/cmd.DayCamera.PrevZoomTablePos]]


### Preconditions

- Day camera must be started




## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :count
- **Precision:** 0



