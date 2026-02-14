---
id: cmd.DayCamera.Move
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Move

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Initiates continuous movement of day camera lens (zoom or focus motor) toward a target position at a specified speed. Movement continues until the target is reached or a Halt command is issued. Both target_value and speed are normalized (0.0-1.0 range). Used as a sub-command within Focus and Zoom composite commands for press-and-hold lens control with ramping acceleration.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target_value | double | >= 0, <= 1 |
| 2 | speed | double | >= 0, <= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :press-accelerating
- **Feedback:** :pending-timeout
- **Timeout (ms):** 2000


### Purpose

Initiates continuous lens motor movement toward target position at specified speed, used for press-and-hold focus/zoom control


### Related State

- [[proto/ser.JonGuiDataCameraDay]] - `focusPos` or `zoomPos` reflects current normalized position


### Related Commands

- [[proto/cmd.DayCamera.Halt]] - Stops motor movement (must be called on button release)
- [[proto/cmd.DayCamera.Focus]] - Parent composite command for focus operations
- [[proto/cmd.DayCamera.Zoom]] - Parent composite command for zoom operations


### Preconditions

- Day camera started
- Auto-focus disabled (for focus moves)


### Implementation Notes

The frontend implements a ramping acceleration pattern:
1. On button press, sends initial `offset` command for immediate tactile feedback
2. After hold threshold, starts sending `move` commands with speed ramping from 0 to 1 over ~2 seconds
3. On button release, sends `halt` to stop motor movement

This is a sub-command within Focus and Zoom composite commands - not sent directly at the root level.



## Field Notes


### target_value (#1)

Target lens position as a normalized value (0.0 = minimum, 1.0 = maximum). Motor moves toward this position until reached or halted. For focus: 0.0 = near, 1.0 = infinity. For zoom: 0.0 = wide, 1.0 = telephoto.


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3
- **Display Format:** `{value * 100}%`


### speed (#2)

Motor movement speed as a normalized value (0.0 = stopped, 1.0 = maximum speed). Frontend typically ramps this from 0 to 1 over ~2 seconds during press-and-hold to provide smooth acceleration.


#### Metadata

- **Semantic Type:** :speed
- **Precision:** 2
- **Display Format:** `Speed: {value * 100}%`



