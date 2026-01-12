---
id: cmd.DayCamera.SetValue
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetValue

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Generic value setter for day camera parameters, accepting a normalized value between 0.0 and 1.0. Used within Focus and Zoom composite commands for direct absolute positioning of camera actuators with slider-based UI patterns and fire-and-forget feedback.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]




### Implementation Notes

Not found in frontend - may be deprecated or internal



