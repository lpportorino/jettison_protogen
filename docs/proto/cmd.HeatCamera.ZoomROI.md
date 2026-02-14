---
id: cmd.HeatCamera.ZoomROI
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ZoomROI

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Zooms the thermal camera to a user-selected rectangular region of interest using normalized device coordinates (NDC), with frame synchronization via timestamps.

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

Defines region to zoom into on thermal camera


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.DayCamera.ZoomROI]]


### Preconditions

- Camera must be started
- User draws rectangle or taps on video overlay


### Implementation Notes

Uses zoomOverlay component. Allows specifying a region to zoom into via rectangle selection or point tap.

- **Rectangle selection**: User draws a rectangle on the video overlay; coordinates are normalized to NDC [-1, 1]
- **Point tap**: Single tap creates a 0.2 NDC region (10% of view) centered on the tap point
- **Timestamp synchronization**: `frame_time` comes from the video frame's timestamp; `state_time` comes from `DeviceStateDispatch.getSystemMonotonicTimeUs()`
- **Validation**: Command is not sent if system monotonic time is unavailable (state not yet received)



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

Frame timestamp for synchronization. Obtained from the video frame data via `DeviceStateDispatch.getHeatFrameData().timestamp`. Used to correlate the ROI selection with the exact video frame the user was viewing when they made the selection.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### state_time (#6)

State snapshot timestamp for synchronization. Obtained from `DeviceStateDispatch.getSystemMonotonicTimeUs()` (system monotonic time from JonGUIState). Used together with frame_time to account for any latency between frame capture and state updates.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds



