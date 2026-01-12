---
id: cmd.DayCamera.Offset
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Offset

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Adjusts day camera focus or zoom position by a relative offset amount. The offset_value is normalized (-1 to 1 range) where negative values move toward minimum and positive toward maximum. Used within Focus and Zoom composite commands for incremental adjustments.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.DayCamera.Focus]]
- [[proto/proto/proto/proto/proto/proto/cmd.DayCamera.Zoom]]



### Implementation Notes

Used within Focus and Zoom submessages



## Field Notes


### offset_value (#1)


#### Metadata

- **Semantic Type:** :raw



