---
id: cmd.HeatCamera.SetFxMode
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetFxMode

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | mode | [[proto/ser.JonGuiDataFxModeHeat]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout


### Purpose

Set FX (image enhancement) mode for heat camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.NextFxMode]]
- [[proto/proto/cmd.HeatCamera.PrevFxMode]]





## Field Notes


### mode (#1)


#### Metadata

- **Semantic Type:** :enum-label



