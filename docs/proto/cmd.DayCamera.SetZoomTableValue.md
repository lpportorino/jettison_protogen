---
id: cmd.DayCamera.SetZoomTableValue
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetZoomTableValue

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.NextZoomTablePos]]
- [[proto/proto/cmd.DayCamera.PrevZoomTablePos]]


### Preconditions

- Day camera must be started




## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :count
- **Precision:** 0



