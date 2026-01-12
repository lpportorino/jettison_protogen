---
id: cmd.HeatCamera.SetDigitalZoomLevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetDigitalZoomLevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets thermal camera digital zoom level (1.0-6.0x). Controls the digital magnification of the thermal image.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 1 |

## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget

### Purpose

Controls the thermal camera digital zoom level. The value represents the zoom multiplier, typically ranging from 1.0x (no zoom) to 6.0x (maximum digital zoom).

### Related State

- [[ser.JonGuiDataCameraHeat]]

### Implementation Notes

Digital zoom is applied in software by cropping and scaling the thermal image. Unlike optical zoom, digital zoom does not increase actual detail but enlarges the center portion of the image. The constraint >= 1 ensures no zoom reduction below 1.0x. Maximum value typically 6.0x based on hardware capabilities.

## Field Notes

### value (#1)

Digital zoom multiplier (1.0x = no zoom, 6.0x = maximum digital zoom).

#### Metadata

- **Semantic Type:** :count
- **Unit:** x
- **Precision:** 1
- **Display Format:** `{value}x`
- **Range:** 1.0 - 6.0



