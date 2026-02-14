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

- [[proto/ser.JonGuiDataCameraHeat]] - Heat camera state
- [[proto/ser.JonGUIState]] - For `systemMonotonicTimeUs` used in `state_time`


### Related Commands

- [[proto/cmd.DayCamera.TrackROI]] - Equivalent command for day camera
- [[proto/cmd.CV.StartTrackNDC]] - Point-based tracking (used when user taps instead of drawing rectangle)
- [[proto/cmd.HeatCamera.ZoomROI]] - Zoom to region instead of track
- [[proto/cmd.HeatCamera.FocusROI]] - Focus on region instead of track


### Preconditions

- Camera must be started and streaming video
- System state must be available (non-zero `systemMonotonicTimeUs`)
- Frame data must be available from video stream
- User draws rectangle on thermal video overlay (triggers TrackROI)


### Implementation Notes

Uses `<tracking-overlay>` Lit component with green theme (`#00ff88`). Rectangle selection (pan gesture) triggers TrackROI, while point selection (tap gesture) triggers CV.StartTrackNDC instead. The overlay converts screen percentage coordinates [0-100] to NDC [-1, 1] via `normalizeROI()`. State time is validated before sending - command is rejected if state has not been received yet.



## Field Notes


### x1 (#1)

Left edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC


### y1 (#2)

Top edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC


### x2 (#3)

Right edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC


### y2 (#4)

Bottom edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC


### frame_time (#5)

Timestamp of the video frame displayed when user initiated tracking. Retrieved from `DeviceStateDispatch.getHeatFrameData().timestamp`. Used for frame-to-action correlation.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### state_time (#6)

System monotonic time from the state snapshot when the user performed the action. Retrieved from `DeviceStateDispatch.getSystemMonotonicTimeUs()`. Used to correlate tracking intent with system state at that moment. <!-- NEEDS_REVIEW: Proto comment says "microseconds" but metadata says "nanoseconds" - verify actual unit convention -->


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds



