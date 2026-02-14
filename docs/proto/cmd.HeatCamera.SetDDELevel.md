---
id: cmd.HeatCamera.SetDDELevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetDDELevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the Digital Detail Enhancement (DDE) level for thermal image processing, controlling edge enhancement intensity. Accepts an integer value from 0 to 100 and uses pending-timeout feedback (2 second timeout) to confirm state synchronization with the camera.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | int32 | >= 0, <= 100 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider-with-presets
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Sets the DDE (Digital Detail Enhancement) level for thermal image processing. Controls edge enhancement intensity to improve thermal image clarity and edge definition.


### Related State

- [[proto/ser.JonGuiDataCameraHeat]] - provides `ddeEnabled` and `ddeLevel` fields


### Related Commands

- [[proto/cmd.HeatCamera.ShiftDDE]] - relative adjustment of DDE level
- [[proto/cmd.HeatCamera.EnableDDE]] - enable DDE processing
- [[proto/cmd.HeatCamera.DisableDDE]] - disable DDE processing


### Preconditions

- Heat camera must be started
- DDE must be enabled (via EnableDDE) for value changes to have effect


### Implementation Notes

The UI provides multiple interaction modes:
1. **Preset buttons**: Quick selection of common levels (3, 10, 30, 50, 100)
2. **Fine-tune steppers**: Plus/minus buttons with press-accelerating behavior for precise adjustment
3. **Keyboard overlay**: Transient overlay (via hotkey) with 'd'=decrease, 'i'=increase, 't'=toggle, adjustable step size



## Field Notes


### value (#1)

DDE enhancement intensity level from 0 (minimal) to 100 (maximum). Higher values increase edge enhancement but may introduce artifacts at extreme settings.

**Recommended levels:**
- 3: Minimal enhancement, preserves natural image
- 10: Subtle enhancement, slight detail improvement
- 30: Moderate enhancement, good balance of detail and naturalness
- 50: Strong enhancement, significant detail improvement
- 100: Maximum enhancement, highest detail but may introduce artifacts


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** (unitless intensity)
- **Precision:** 0
- **Display Format:** `Level: {value}`



