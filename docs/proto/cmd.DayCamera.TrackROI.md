---
id: cmd.DayCamera.TrackROI
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# TrackROI

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Initiates continuous video tracking on a specified rectangular region of interest (ROI) in the day camera feed. Uses normalized device coordinates (NDC, -1 to 1 range) with frame and system timestamps for synchronization with the camera stream.

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
- **Feedback:** :poll-confirm


### Purpose

Start video tracking on specified Region of Interest (ROI) in day camera


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.ZoomROI]]
- [[proto/cmd.DayCamera.FocusROI]]


### Preconditions

- Day camera must be started
- Frame data must be available
- System monotonic time must be synced


### Implementation Notes

ROI specified as normalized coordinates with frame timestamp for synchronization



## Field Notes


### x1 (#1)

Left edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Display Format:** `X1 coordinate`


### y1 (#2)

Top edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Display Format:** `Y1 coordinate`


### x2 (#3)

Right edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Display Format:** `X2 coordinate`


### y2 (#4)

Bottom edge in NDC (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Display Format:** `Y2 coordinate`


### frame_time (#5)

Frame timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### state_time (#6)

State snapshot timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds



