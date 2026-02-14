---
id: cmd.DayCamera.SetAutoGain
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetAutoGain

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Enables or disables automatic gain control (AGC) for the day camera. When enabled, the camera automatically adjusts hardware gain to optimize brightness based on scene lighting. Exposed in the UI as a toggle control with pending-timeout feedback (waits up to 2 seconds for state confirmation).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Enable or disable automatic gain control for day camera


### Related State

- [[proto/ser.JonGuiDataCameraDay]] (autoGain field)


### Related Commands

- [[proto/cmd.DayCamera.SetAutoIris]]


### Preconditions

- Day camera started


### Implementation Notes

When enabled, camera automatically adjusts hardware gain to optimize brightness based on scene lighting. Works alongside auto-iris for full automatic exposure control.






## Field Notes


### value (#1)

Enable/disable state


#### Metadata

- **Semantic Type:** :toggle-state



