---
id: cmd.HeatCamera.SetFilters
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetFilters

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the color filter mode for thermal camera display by accepting a JonGuiDataVideoChannelHeatFilters enum value. Cycles through available filter modes (HOT_WHITE, HOT_BLACK, SEPIA, SEPIA_INVERSE) to provide different color palettes for thermal image visualization. HOT_WHITE shows hottest objects in white, HOT_BLACK shows hottest objects in black, SEPIA provides a sepia tone color scheme, and SEPIA_INVERSE provides an inverted sepia tone.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | [[proto/ser.JonGuiDataVideoChannelHeatFilters]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout


### Purpose

Sets color filter mode for thermal camera display


### Related State

- [[proto/ser.JonGuiDataCameraHeat]] (filter field)


### Related Commands

- [[proto/cmd.HeatCamera.SetAGC]]


### Preconditions

- Heat camera started


### Implementation Notes

UI presents a horizontal button palette with 4 filter options: Hot White, Hot Black, Sepia, Sepia Inv. Uses pending state with 2000ms timeout while waiting for state confirmation.



## Field Notes


### value (#1)

See related enum for valid values


#### Metadata

- **Semantic Type:** :enum-label
- **Presets:** HOT_WHITE, HOT_BLACK, SEPIA, SEPIA_INVERSE



