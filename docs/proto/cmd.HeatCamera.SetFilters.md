---
id: cmd.HeatCamera.SetFilters
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetFilters

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the color filter mode for thermal camera display by accepting a JonGuiDataVideoChannelHeatFilters enum value. Cycles through available filter modes (HOT_BLACK, HOT_WHITE, SEPIA) to provide different color palettes for thermal image visualization.

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

- [[proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/cmd.HeatCamera.SetAGC]]


### Preconditions

- Heat camera started


### Implementation Notes

Cycles through filter modes (hot_black, hot_white, sepia) for different viewing preferences



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Presets:** HOT_BLACK, HOT_WHITE, SEPIA



