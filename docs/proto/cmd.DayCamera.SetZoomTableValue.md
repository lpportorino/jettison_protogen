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
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Sets day camera zoom to specific table position by index. The zoom table contains predefined optical magnification levels (typically 5 levels labeled I through V for the day camera). This command allows direct selection of a table entry rather than stepping through with Next/Prev commands.


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.NextZoomTablePos]]
- [[proto/cmd.DayCamera.PrevZoomTablePos]]
- [[proto/cmd.DayCamera.SaveToTable]]


### Preconditions

- Day camera must be started
- Index must be within range [0, zoomTablePosMax]


### Implementation Notes

Typically used in zoom palette UI where users can click directly on preset buttons (I, II, III, IV, V) rather than stepping through. When cameras are synced, both day and heat cameras receive the same zoom table index. The pending state clears after 2 seconds or when the state confirms the new position.




## Field Notes


### value (#1)

Zero-based index into the camera's zoom table. Valid range is 0 to zoomTablePosMax (typically 0-4 for day camera with 5 preset levels). Each index corresponds to a predefined optical magnification level.


#### Metadata

- **Semantic Type:** :count
- **Unit:** (index)
- **Precision:** 0



