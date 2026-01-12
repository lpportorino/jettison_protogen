---
id: cmd.HeatCamera.FxROI
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FxROI

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Specifies a rectangular region of interest for thermal camera AGC/exposure optimization and post-processing effects. The region is defined by corner coordinates in normalized device coordinates (NDC, -1 to 1 range) with frame and state timestamps for synchronization.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | x1 | double | >= -1, <= 1 |
| 2 | y1 | double | >= -1, <= 1 |
| 3 | x2 | double | >= -1, <= 1 |
| 4 | y2 | double | >= -1, <= 1 |
| 5 | frame_time | uint64 | - |
| 6 | state_time | uint64 | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :fire-and-forget


### Purpose

Defines region of interest for AGC/exposure optimization and post-processing effects on thermal camera


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.DayCamera.FxROI]]


### Preconditions

- Camera must be started
- User draws rectangle or taps on video overlay


### Implementation Notes

Uses fxOverlay component with amber/golden theme. User can either draw a rectangle or tap a point (creates 5% region around tap). Requires frame timestamp synchronization.



## Field Notes


### x1 (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC


### y1 (#2)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC


### x2 (#3)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC


### y2 (#4)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC


### frame_time (#5)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### state_time (#6)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds



