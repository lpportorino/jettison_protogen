---
id: cmd.DayCamera.ZoomROI
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# ZoomROI

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
- **Feedback:** :fire-and-forget


### Purpose

Zooms the day camera to focus on a region of interest marked by user on video


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.TrackROI]]
- [[proto/proto/cmd.DayCamera.FocusROI]]


### Preconditions

- Day camera started
- Valid frame timestamp


### Implementation Notes

Requires NDC coordinates (-1 to 1) and frame/state timestamps for synchronization



## Field Notes


### x1 (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3
- **Display Format:** `{value}`


### y1 (#2)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3
- **Display Format:** `{value}`


### x2 (#3)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3
- **Display Format:** `{value}`


### y2 (#4)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3
- **Display Format:** `{value}`


### frame_time (#5)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns


### state_time (#6)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns



