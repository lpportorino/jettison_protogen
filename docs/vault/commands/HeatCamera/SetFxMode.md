---
id: cmd.HeatCamera.SetFxMode
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetFxMode

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the thermal visual effects/filter mode for the heat camera.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | mode | [[ser.JonGuiDataFxModeHeat]] | defined enum value only, not in: 0 |

## Interaction

- **Category:** :actuator
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Controls the thermal visual effects/filter mode applied to the heat camera image processing pipeline.

### Related State

- [[ser.JonGuiDataCameraHeat]]

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator.



