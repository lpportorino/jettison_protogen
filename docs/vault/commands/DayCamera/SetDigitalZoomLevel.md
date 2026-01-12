---
id: cmd.DayCamera.SetDigitalZoomLevel
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetDigitalZoomLevel

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the digital zoom level for the day camera. Controls the amount of digital magnification applied to the image.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 1 |

## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Controls the digital zoom magnification of the day camera. Digital zoom crops and scales the image to provide magnification. Value of 1.0 represents no zoom (1x), while higher values provide increasing magnification.

### Related State

- [[ser.JonGuiDataCameraDay]]

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. Digital zoom degrades image quality as it's essentially cropping and scaling, unlike optical zoom. Consider displaying a visual indicator of image quality degradation at higher zoom levels. Maximum zoom level should be validated against camera capabilities (typically 6.0x based on common camera specs).

## Field Notes

### value (#1)

Digital zoom multiplier. 1.0 = no zoom (1x), higher values increase magnification.

#### Metadata

- **Semantic Type:** :count
- **Unit:** x
- **Precision:** 1
- **Display Format:** `{value}x`
- **Range:** 1.0x to 6.0x (typical maximum)



