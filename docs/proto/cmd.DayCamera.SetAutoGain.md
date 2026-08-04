---
id: cmd.DayCamera.SetAutoGain
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetAutoGain

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Enables or disables automatic gain control (AGC) for the day camera. When enabled, the camera automatically adjusts hardware gain to optimize brightness based on scene lighting. Exposed in the UI as a toggle control with fire-and-forget feedback.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Enable or disable automatic gain control for day camera


### Related State

- [[proto/ser.JonGuiDataCameraDay#auto_gain]]






## Field Notes


### value (#1)

Enable/disable state


#### Metadata

- **Semantic Type:** :toggle-state



