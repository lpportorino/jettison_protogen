---
id: cmd.DayCamera.SetValue
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetValue

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Generic value setter for day camera parameters


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]




### Implementation Notes

Not found in frontend - may be deprecated or internal



