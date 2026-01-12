---
id: cmd.DayCamera.SetIris
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetIris

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the day camera iris/aperture position. Controls the amount of light reaching the sensor by adjusting the physical aperture opening.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider-with-presets
- **Feedback:** :pending-timeout


### Purpose

Controls the physical iris aperture of the day camera. Adjusting this changes the amount of light reaching the sensor and affects depth of field.


### Related State

- [[ser.JonGuiDataCameraDay]]


### Related Commands

- [[cmd.DayCamera.SetAutoIris]]


### Preconditions

- Camera must be started
- Auto-iris must be disabled


### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. If auto-iris is enabled, disable it first before sending manual iris commands. The slider should show 11 preset buttons (0%, 10%, 20%, ..., 100%) plus an "Auto" toggle.



## Field Notes


### value (#1)

Normalized iris position (0.0 = closed, 1.0 = fully open).


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`
- **Presets:** 0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0



