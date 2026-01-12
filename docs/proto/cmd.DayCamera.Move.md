---
id: cmd.DayCamera.Move
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Move

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Moves day camera lens (zoom or focus) to a target position at a specified speed. Both target_value and speed are normalized (0-1 range). Used within Focus and Zoom composite commands for smooth, controlled lens movement with variable speed control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target_value | double | >= 0, <= 1 |
| 2 | speed | double | >= 0, <= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :optimistic-visual


### Purpose

Moves day camera lens (zoom or focus) to target position at specified speed


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]



### Preconditions

- Day camera started


### Implementation Notes

Part of zoom/focus control, provides smooth movement with speed control



## Field Notes


### target_value (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3
- **Display Format:** `{value}`


### speed (#2)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 2
- **Display Format:** `Speed: {value}`



