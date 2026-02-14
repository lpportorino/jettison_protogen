---
id: cmd.DayCamera.SetInfraRedFilter
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetInfraRedFilter

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Enables or disables the infrared filter on the day camera to block IR light for better color reproduction in visible light conditions. The UI displays a pending state until the camera confirms the filter position change (typically within 2 seconds).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | bool | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Enable or disable infrared filter on day camera. When enabled (true), the IR-cut filter blocks infrared light for accurate color reproduction in daylight. When disabled (false), infrared light passes through, improving low-light/night vision sensitivity at the cost of color accuracy.


### Related State

- [[proto/ser.JonGuiDataCameraDay]] - `infraredFilter` field reflects current filter state


### Related Commands

- [[proto/cmd.DayCamera.Start]] - Camera must be started before controlling IR filter






## Field Notes


### value (#1)

Controls the IR-cut filter state. `true` enables the filter (blocks IR light, normal daylight operation), `false` disables it (passes IR light, enhanced low-light sensitivity).


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Enabled/Disabled`



