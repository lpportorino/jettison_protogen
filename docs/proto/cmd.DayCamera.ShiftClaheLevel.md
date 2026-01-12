---
id: cmd.DayCamera.ShiftClaheLevel
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# ShiftClaheLevel

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -1, <= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Adjust CLAHE (Contrast Limited Adaptive Histogram Equalization) level incrementally


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.SetClaheLevel]]



### Implementation Notes

Used in transient overlay with keyboard shortcuts, shifts by ±0.01, clamped to [0, 1]



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** normalized
- **Precision:** 2
- **Display Format:** `Shift value (±0.01)`



