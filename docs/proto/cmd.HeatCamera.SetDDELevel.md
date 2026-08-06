---
id: cmd.HeatCamera.SetDDELevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetDDELevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the Digital Detail Enhancement (DDE) level for thermal image processing, controlling edge enhancement intensity. Accepts an integer value from 0 to 100 and is typically controlled via a slider UI with fire-and-forget feedback.

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

- [[proto/ser.JonGuiDataCameraHeat#dde_level]]


### Related Commands

- [[proto/cmd.HeatCamera.ShiftDDE]]
- [[proto/cmd.HeatCamera.EnableDDE]]
- [[proto/cmd.HeatCamera.DisableDDE]]


### Preconditions

- Heat camera must be started


### Implementation Notes

Adjusts edge enhancement intensity for thermal imagery



## Field Notes


### value (#1)

Percentage value (0-100)


#### Metadata

- **Semantic Type:** :percentage
- **Precision:** 2
- **Display Format:** `Level: {value}`



