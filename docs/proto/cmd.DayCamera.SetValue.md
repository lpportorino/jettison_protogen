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

- [[proto/ser.JonGuiDataCameraDay]]




### Related Commands

- [[proto/cmd.DayCamera.Focus]] - Parent composite command for focus operations
- [[proto/cmd.DayCamera.Zoom]] - Parent composite command for zoom operations
- [[proto/cmd.DayCamera.Offset]] - Alternative sub-command for relative adjustments
- [[proto/cmd.DayCamera.Move]] - Alternative sub-command for continuous movement


### Implementation Notes

Used in the frontend via `dayCameraSetFocus(value)` and `dayCameraSetZoom(value)` functions in `cmdDayCamera.ts`. These create Focus or Zoom composite commands with the `setValue` sub-command containing the normalized position. Provides direct absolute positioning as an alternative to relative `offset` or continuous `move` commands



## Field Notes


### value (#1)

Normalized value (0.0 to 1.0) representing the absolute position of the actuator (focus or zoom). Value 0.0 represents the minimum position and 1.0 represents the maximum position.


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** (unitless, 0-1 range)
- **Precision:** 4 decimal places (0.0001 granularity typical for motor positioning)



