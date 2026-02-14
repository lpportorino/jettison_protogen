---
id: cmd.HeatCamera.ShiftClaheLevel
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ShiftClaheLevel

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Incremental adjustment command for thermal camera CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level. Accepts a normalized shift value between -1.0 and 1.0 to adjust the contrast enhancement by relative increments. Used by keyboard shortcuts in the Heat CLAHE Control transient overlay, where 'd' decreases by 1% (-0.01) and 'i' increases by 1% (+0.01). The shift is applied relative to the current `claheLevel` in `ser.JonGuiDataCameraHeat`.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -1, <= 1 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Adjusts thermal CLAHE level incrementally via keyboard shortcuts. In the Heat CLAHE Control transient overlay (`jon-transient-heat-clahe-overlay`), pressing 'd' sends `shiftClaheLevel(-0.01)` to decrease by 1%, and pressing 'i' sends `shiftClaheLevel(0.01)` to increase by 1%. The frontend checks current `claheLevel` bounds before sending (only decreases if > 0, only increases if < 1).


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.SetClaheLevel]]


### Preconditions

- Heat camera must be started




## Field Notes


### value (#1)

Signed offset value (-1.0 to 1.0) representing the relative change to apply to the current CLAHE level. Positive values increase contrast enhancement, negative values decrease it. Typical increment is 0.01 (1% change per key press).


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** (relative)
- **Precision:** 2
- **Display Format:** `{value > 0 ? '+' : ''}{value * 100}%`
- **Presets:** -0.01, +0.01



