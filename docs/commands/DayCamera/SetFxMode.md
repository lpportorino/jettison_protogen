---
id: cmd.DayCamera.SetFxMode
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetFxMode

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the visual effects/filter mode (A-F modes) for the day camera.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | mode | [[ser.JonGuiDataFxModeDay]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout


### Purpose

Controls the visual effects/filter mode applied to the day camera image processing pipeline.


### Related State

- [[ser.JonGuiDataCameraDay]]




### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator.



