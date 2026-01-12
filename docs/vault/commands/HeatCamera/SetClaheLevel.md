---
id: cmd.HeatCamera.SetClaheLevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetClaheLevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level for the thermal camera. Improves visibility of thermal gradients and details.

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

Adjusts the intensity of CLAHE enhancement on thermal imagery. Higher values increase local contrast to reveal subtle temperature differences.

### Related State

- [[ser.JonGuiDataCameraHeat]]

### Implementation Notes

Slider with ± step buttons for 1% increments. Visual position maintained during drag, shown as pending until server confirms.

## Field Notes

### value (#1)

Normalized CLAHE intensity level (0.0 = none, 1.0 = maximum enhancement).

#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`



