---
id: cmd.HeatCamera.SetClaheLevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetClaheLevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the CLAHE (Contrast Limited Adaptive Histogram Equalization) level for the thermal camera to control image contrast enhancement. Accepts a normalized value (0-1, displayed as 0-100%) with preset options and 5% increment/decrement capability via slider UI.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :slider-with-presets
- **Feedback:** :fire-and-forget


### Purpose

Set CLAHE (contrast limited adaptive histogram equalization) level for heat camera


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]




### Implementation Notes

Preset values: 0%, 10%, 25%, 50%, 75%, 100%; can increment/decrement by 5%



## Field Notes


### value (#1)

Normalized value (0.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 2
- **Display Format:** `{value * 100}%`



