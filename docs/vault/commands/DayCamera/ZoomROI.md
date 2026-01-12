---
id: cmd.DayCamera.ZoomROI
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# ZoomROI

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Selects a region of interest (ROI) for digital zoom in the day camera viewport. The ROI is defined by two corner points in viewport coordinates.

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
- **Timeout:** 2000ms

### Purpose

Defines a rectangular region of interest in the camera viewport for digital zoom. The camera will zoom in to display the selected region at full resolution.

### Related State

- [[ser.JonGuiDataCameraDay]]

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The UI should allow users to select a rectangular region by dragging on the video viewport. Coordinates are normalized to viewport space (-1 to 1) where (-1, -1) is top-left and (1, 1) is bottom-right.

## Field Notes

### x1 (#1)

X coordinate of the first corner point of the ROI rectangle.

#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Unit:** normalized
- **Range:** -1 to 1
- **Description:** -1 = left edge, 1 = right edge

### y1 (#2)

Y coordinate of the first corner point of the ROI rectangle.

#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Unit:** normalized
- **Range:** -1 to 1
- **Description:** -1 = top edge, 1 = bottom edge

### x2 (#3)

X coordinate of the second corner point of the ROI rectangle.

#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Unit:** normalized
- **Range:** -1 to 1
- **Description:** -1 = left edge, 1 = right edge

### y2 (#4)

Y coordinate of the second corner point of the ROI rectangle.

#### Metadata

- **Semantic Type:** :coordinate-viewport
- **Unit:** normalized
- **Range:** -1 to 1
- **Description:** -1 = top edge, 1 = bottom edge



