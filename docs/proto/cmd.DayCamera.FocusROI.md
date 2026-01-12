---
id: cmd.DayCamera.FocusROI
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# FocusROI

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

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
- **Feedback:** :pending-timeout


### Purpose

Focus on region of interest (ROI) in day camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.Focus]]
- [[proto/proto/cmd.DayCamera.TrackROI]]
- [[proto/proto/cmd.DayCamera.ZoomROI]]



### Implementation Notes

User draws rectangle on video to focus



## Field Notes


### x1 (#1)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** px


### y1 (#2)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** px


### x2 (#3)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** px


### y2 (#4)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** px


### frame_time (#5)


#### Metadata

- **Semantic Type:** :timestamp


### state_time (#6)


#### Metadata

- **Semantic Type:** :timestamp



