---
id: cmd.HeatCamera.FocusROI
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusROI

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Focuses the thermal camera on a user-selected rectangular region of interest (ROI). The ROI is defined by normalized coordinates (x1,y1) to (x2,y2) in the -1 to 1 range, with frame and state timestamps for synchronization with the video stream.

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
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Focuses camera on user-selected region of interest


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.TrackROI]]
- [[proto/proto/cmd.HeatCamera.ZoomROI]]



### Implementation Notes

User draws rectangle on video to select focus area



## Field Notes


### x1 (#1)


#### Metadata

- **Semantic Type:** :coordinate-geo


### y1 (#2)


#### Metadata

- **Semantic Type:** :coordinate-geo


### x2 (#3)


#### Metadata

- **Semantic Type:** :coordinate-geo


### y2 (#4)


#### Metadata

- **Semantic Type:** :coordinate-geo


### frame_time (#5)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds


### state_time (#6)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds



