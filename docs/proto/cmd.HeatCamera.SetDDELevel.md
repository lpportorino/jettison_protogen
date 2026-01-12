---
id: cmd.HeatCamera.SetDDELevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetDDELevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | int32 | >= 0, <= 100 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Sets the DDE (Digital Detail Enhancement) level for thermal image processing


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.ShiftDDE]]
- [[proto/proto/cmd.HeatCamera.EnableDDE]]
- [[proto/proto/cmd.HeatCamera.DisableDDE]]



### Implementation Notes

Adjusts edge enhancement intensity for thermal imagery



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 2
- **Display Format:** `Level: {value}`



