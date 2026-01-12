---
id: cmd.HeatCamera.ShiftClaheLevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ShiftClaheLevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -1, <= 1 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Adjusts thermal CLAHE level incrementally


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.SetClaheLevel]]


### Preconditions

- Heat camera must be started




## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`
- **Presets:** 0.1, 0.25, 0.5, 0.75, 1.0



