---
id: cmd.DayCamera.SetIris
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetIris

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Controls the camera iris (aperture) opening to adjust light intake and depth of field. The normalized value (0-1) represents aperture opening percentage, with UI presets at 0%, 3%, 5%, 7%, 10%, 15%, 20%, 30%, 50%, 75%, 100%, and Auto mode.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider-with-presets
- **Feedback:** :pending-timeout


### Purpose

Controls the camera iris (aperture) opening to adjust light intake and depth of field


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.SetAutoIris]]


### Preconditions

- Camera must be started
- Auto-iris should be disabled for manual control


### Implementation Notes

Uses jonIrisPalette component with slider + steppers + preset buttons. Presets: 0%, 3%, 5%, 7%, 10%, 15%, 20%, 30%, 50%, 75%, 100%, Auto. Optimistic UI with visual position during drag, pending state until server confirms.



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`
- **Presets:** 0.0, 0.03, 0.05, 0.07, 0.1, 0.15, 0.2, 0.3, 0.5, 0.75, 1.0, auto



