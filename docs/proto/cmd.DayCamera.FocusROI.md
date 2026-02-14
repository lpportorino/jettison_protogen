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
- **UI Pattern:** :roi-selection
- **Feedback:** :pending-timeout


### Purpose

Triggers auto-focus on a user-selected region of interest (ROI) in the day camera video feed


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.Focus]]
- [[proto/cmd.DayCamera.TrackROI]]
- [[proto/cmd.DayCamera.ZoomROI]]
- [[proto/cmd.CV.SetAutoFocus]]


### Preconditions

- Day camera must be started
- Frame data must be available
- System monotonic time must be synced


### Implementation Notes

User draws a rectangle on the video overlay (or taps a point to create a small focus region). The frontend converts screen coordinates to NDC (-1 to 1), captures the current frame timestamp, and sends the command. The camera's autofocus algorithm then optimizes focus within the specified region.



## Field Notes


### x1 (#1)

Left edge of the focus region in NDC (-1.0 to 1.0). The value -1 corresponds to the left edge of the viewport, +1 to the right edge.


#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Precision:** 3
- **Display Format:** `{value}`


### y1 (#2)

Top edge of the focus region in NDC (-1.0 to 1.0). The value -1 corresponds to the top edge of the viewport, +1 to the bottom edge.


#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Precision:** 3
- **Display Format:** `{value}`


### x2 (#3)

Right edge of the focus region in NDC (-1.0 to 1.0). Must be greater than x1 to define a valid region.


#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Precision:** 3
- **Display Format:** `{value}`


### y2 (#4)

Bottom edge of the focus region in NDC (-1.0 to 1.0). Must be greater than y1 to define a valid region.


#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Precision:** 3
- **Display Format:** `{value}`


### frame_time (#5)

Timestamp of the video frame when the ROI was selected. Used to correlate the ROI coordinates with the correct frame in the video stream, ensuring focus is applied to the intended region even with pipeline latency.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns


### state_time (#6)

System monotonic time when the command was created. Used for synchronization with system state and to track command latency.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** us



