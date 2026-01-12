---
id: cmd.HeatCamera.SetAGC
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetAGC

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.NextFxMode]]
- [[proto/proto/cmd.HeatCamera.SetFilters]]


### Preconditions

- Heat camera started


### Implementation Notes

Cycles through AGC modes (MODE_1, MODE_2, MODE_3) for different viewing conditions



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Presets:** MODE_1, MODE_2, MODE_3



