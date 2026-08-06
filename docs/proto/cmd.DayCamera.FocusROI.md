---
id: cmd.DayCamera.FocusROI
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# FocusROI

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Triggers auto-focus on a user-defined region of interest (ROI) in the day camera feed. The user draws a rectangle on the video display (or taps a point), and the camera adjusts focus to optimize sharpness within that region. Uses NDC coordinates (-1 to 1 range).

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

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.Focus]]
- [[proto/cmd.DayCamera.TrackROI]]
- [[proto/cmd.DayCamera.ZoomROI]]


### Preconditions

- Day camera must be started
- User draws rectangle or taps on video overlay


### Implementation Notes

User draws rectangle on video to focus



## Field Notes


### x1 (#1)

Left edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** NDC


### y1 (#2)

Top edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** NDC


### x2 (#3)

Right edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** NDC


### y2 (#4)

Bottom edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** NDC


### frame_time (#5)

Frame timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp


### state_time (#6)

State snapshot timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp



