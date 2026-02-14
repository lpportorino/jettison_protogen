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
- **UI Pattern:** :roi-selection
- **Feedback:** :pending-timeout


### Purpose

Focuses camera on user-selected region of interest


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.TrackROI]]
- [[proto/cmd.HeatCamera.ZoomROI]]



### Implementation Notes

User draws rectangle on video to select focus area



## Field Notes


### x1 (#1)

Left edge of focus region in NDC (-1.0 to 1.0). Together with x2, defines the horizontal bounds of the region where autofocus will be performed.


#### Metadata

- **Semantic Type:** :coordinate-viewport


### y1 (#2)

Top edge of focus region in NDC (-1.0 to 1.0). Together with y2, defines the vertical bounds of the region where autofocus will be performed.


#### Metadata

- **Semantic Type:** :coordinate-viewport


### x2 (#3)

Right edge of focus region in NDC (-1.0 to 1.0). Together with x1, defines the horizontal bounds of the region where autofocus will be performed.


#### Metadata

- **Semantic Type:** :coordinate-viewport


### y2 (#4)

Bottom edge of focus region in NDC (-1.0 to 1.0). Together with y1, defines the vertical bounds of the region where autofocus will be performed.


#### Metadata

- **Semantic Type:** :coordinate-viewport


### frame_time (#5)

Frame timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds


### state_time (#6)

State snapshot timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds



