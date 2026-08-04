---
id: cmd.DayCamera.SetClaheLevel
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetClaheLevel

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level for the day camera to improve image contrast and visibility. Accepts a normalized value (0-1, displayed as 0-100%) with presets at 0%, 25%, 50%, 75%, and 100%, controlled through UI sliders with pending-timeout feedback.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :slider
- **Feedback:** :pending-timeout


### Purpose

Set CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level for day camera


### Related State

- [[proto/ser.JonGuiDataCameraDay#clahe_level]]


### Related Commands

- [[proto/cmd.DayCamera.ShiftClaheLevel]]


### Preconditions

- Day camera must be started




## Field Notes


### value (#1)

Normalized value (0.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`
- **Presets:** 0.0, 0.25, 0.5, 0.75, 1.0



