---
id: cmd.DayCamera.SetDigitalZoomLevel
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetDigitalZoomLevel

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Controls the digital zoom magnification level for the day camera. Accepts a value representing zoom magnification (1x or higher) and operates as a fire-and-forget command with slider UI. Can be synchronized with heat camera digital zoom via a UI sync toggle for coordinated dual-camera operation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Controls digital zoom magnification level for day camera


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.SetZoomTableValue]]
- [[proto/cmd.HeatCamera.SetDigitalZoomLevel]]



### Implementation Notes

- Can be synchronized with heat camera digital zoom via UI sync toggle
- Frontend uses 2000ms timeout for pending state confirmation
- Value is applied in GPU FX pipeline PRE stage (before encoding)



## Field Notes


### value (#1)

Digital zoom magnification factor. Minimum is 1.0 (no magnification), maximum is 6.0 (6x magnification). Applied as software zoom in the GPU FX pipeline after optical zoom.


#### Metadata

- **Semantic Type:** :multiplier
- **Unit:** x
- **Precision:** 1
- **Display Format:** `{value}x`
- **Range:** 1.0 - 6.0
- **Step:** 0.5



