---
id: cmd.HeatCamera.ShiftDDE
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ShiftDDE

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Incremental adjustment command for thermal camera DDE (Digital Detail Enhancement) level. Accepts positive or negative shift values between -100 and 100, typically used with keyboard shortcuts that shift the DDE level by ±15 increments.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | int32 | >= -100, <= 100 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Adjust DDE (Digital Detail Enhancement) level incrementally for heat camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.SetDDELevel]]
- [[proto/proto/cmd.HeatCamera.EnableDDE]]
- [[proto/proto/cmd.HeatCamera.DisableDDE]]



### Implementation Notes

Used with keyboard shortcuts to shift DDE level by ±15



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :raw
- **Display Format:** `Shift value (positive or negative)`



