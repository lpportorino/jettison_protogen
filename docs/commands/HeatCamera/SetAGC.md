---
id: cmd.HeatCamera.SetAGC
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetAGC

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the Automatic Gain Control mode for the thermal camera. AGC determines how the thermal image contrast is automatically adjusted based on scene temperature range.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | [[ser.JonGuiDataVideoChannelHeatAGCModes]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout


### Purpose

Selects the gain control algorithm for thermal image processing. Different modes optimize for different scene types (linear for broad ranges, histogram for local detail, plateau for mixed scenes).


### Related State

- [[ser.JonGuiDataCameraHeat]]




### Implementation Notes

Display as 3 mutually exclusive buttons (A/B/C pattern). Only one mode can be active at a time. Show pending state during mode transition.



## Field Notes


### value (#1)

The AGC mode to apply.


#### Metadata

- **Semantic Type:** :enum-label



