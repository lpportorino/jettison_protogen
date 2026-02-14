---
id: cmd.CV.StartTrackNDC
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# StartTrackNDC

**Source:** `jon_shared_cmd_cv.proto`

## Description

Initiates object tracking at a specific point using normalized device coordinates (NDC), where the user clicks on a video feed to begin tracking an object at that location. Includes frame and state timestamps for synchronization between frontend and backend video processing pipelines.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | channel | [[proto/ser.JonGuiDataVideoChannel]] | defined enum value only, not in: 0 |
| 2 | x | double | >= -1, <= 1 |
| 3 | y | double | >= -1, <= 1 |
| 4 | frame_time | uint64 | - |
| 5 | state_time | uint64 | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :roi-selection
- **Feedback:** :fire-and-forget


### Purpose

Starts video tracking at normalized device coordinates



### Related Commands

- [[proto/cmd.CV.StopTrack]]



### Implementation Notes

Triggered by single tap on video stream, point selection in tracking overlay, or short swipe. The frontend converts viewport-normalized coordinates (0-1) to NDC (-1 to 1) before sending. Frame and state timestamps are retrieved from `DeviceStateDispatch` to synchronize with the current video frame being displayed.



## Field Notes


### channel (#1)

- **Semantic Type:** :enum-label

Specifies which video channel (day or thermal) the tracking coordinates apply to. Required because each channel has independent resolution and processing pipelines.


### x (#2)

- **Semantic Type:** :coordinate-viewport

X coordinate in NDC (-1.0 to 1.0). Negative values indicate left of center, positive values indicate right of center.


### y (#3)

- **Semantic Type:** :coordinate-viewport

Y coordinate in NDC (-1.0 to 1.0). Positive values indicate above center, negative values indicate below center. Note: The frontend inverts Y when converting from viewport coordinates.


### frame_time (#4)

- **Semantic Type:** :timestamp

Frame PTS (presentation timestamp) in nanoseconds from the video frame being displayed when the user initiated tracking. Used to correlate the tracking request with the exact video frame the user was viewing.


### state_time (#5)

- **Semantic Type:** :timestamp

System monotonic time in microseconds from the most recent state update. Provides temporal context for synchronizing tracking initiation with system state (e.g., turret position, zoom level).



