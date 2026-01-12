---
id: cmd.HeatCamera.ShiftClaheLevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ShiftClaheLevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Incremental adjustment command for thermal camera CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level. Accepts a normalized shift value between -1.0 and 1.0 to adjust the contrast enhancement by relative increments via keyboard shortcuts or steppers.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.SetClaheLevel]]


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



