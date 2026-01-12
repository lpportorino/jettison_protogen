---
id: cmd.DayCamera.Offset
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Offset

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | offset_value | double | >= -1, <= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :pending-timeout


### Purpose

Offset focus or zoom by relative amount


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.Focus]]
- [[proto/proto/cmd.DayCamera.Zoom]]



### Implementation Notes

Used within Focus and Zoom submessages



## Field Notes


### offset_value (#1)


#### Metadata

- **Semantic Type:** :raw



