---
id: cmd.HeatCamera.SetFilters
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetFilters

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the thermal color filter/palette for the heat camera. Controls how temperature data is visualized using different color schemes.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | [[ser.JonGuiDataVideoChannelHeatFilters]] | defined enum value only, not in: 0 |

## Interaction

- **Category:** :actuator
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Controls the color palette used to visualize thermal data from the heat camera. Different palettes provide better contrast for different temperature ranges and viewing conditions.

### Related State

- [[ser.JonGuiDataCameraHeat]]

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The UI should display visual previews of each color palette to help users choose the most appropriate one for their viewing conditions. The value 0 (UNSPECIFIED) is not allowed and should be filtered out from the UI picker. Consider grouping palettes by category (e.g., grayscale, rainbow, high-contrast).

## Field Notes

### value (#1)

The thermal color palette to apply. Must be a defined enum value from [[ser.JonGuiDataVideoChannelHeatFilters]], excluding the UNSPECIFIED value (0).

#### Metadata

- **Semantic Type:** :enum
- **UI Component:** dropdown or button group with palette previews
- **Validation:** Must be a valid enum value (not 0)



