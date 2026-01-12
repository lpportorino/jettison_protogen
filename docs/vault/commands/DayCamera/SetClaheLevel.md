---
id: cmd.DayCamera.SetClaheLevel
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetClaheLevel

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level for the day camera. CLAHE improves local contrast and visibility in challenging lighting conditions.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |

## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider-with-steppers
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Adjusts the intensity of CLAHE image enhancement. Higher values increase local contrast but may introduce noise in uniform areas.

### Related State

- [[ser.JonGuiDataCameraDay]]

### Implementation Notes

Slider with ± step buttons for 1% increments. Visual position should be maintained during drag and shown as pending until server confirms. Epsilon of 0.005 for position comparison to prevent jitter.

## Field Notes

### value (#1)

Normalized CLAHE intensity level (0.0 = none, 1.0 = maximum enhancement).

#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`



