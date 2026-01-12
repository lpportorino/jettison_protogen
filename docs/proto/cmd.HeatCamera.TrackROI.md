---
id: cmd.HeatCamera.TrackROI
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# TrackROI

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Defines a normalized coordinate region for object tracking on the thermal camera. Sent when a user draws a rectangle on the thermal video feed, specifying the region of interest (ROI) to be tracked along with frame and state timestamps for synchronization.

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

Defines region for object tracking on thermal camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.DayCamera.TrackROI]]
- [[proto/proto/cmd.CV.StartTrackNDC]]


### Preconditions

- Camera must be started
- User draws rectangle or taps on video overlay


### Implementation Notes

Uses trackingOverlay component with green theme. Rectangle selection for ROI-based tracking or point selection for CV-based tracking.



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



