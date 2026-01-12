---
id: cmd.HeatCamera.SetAGC
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetAGC

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Configures the Automatic Gain Control (AGC) mode for the thermal camera to optimize image enhancement for different viewing conditions. Accepts an enumerated value (MODE_1, MODE_2, or MODE_3) that adjusts how the camera processes thermal intensity for display.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | [[proto/ser.JonGuiDataVideoChannelHeatAGCModes]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout


### Purpose

Sets automatic gain control mode for thermal camera image enhancement


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.NextFxMode]]
- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.SetFilters]]


### Preconditions

- Heat camera started


### Implementation Notes

Cycles through AGC modes (MODE_1, MODE_2, MODE_3) for different viewing conditions



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Presets:** MODE_1, MODE_2, MODE_3



