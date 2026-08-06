---
id: cmd.HeatCamera.Zoom
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Zoom

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Controls the thermal camera's optical zoom by setting discrete zoom table positions. Supports setting a specific zoom value, moving to the next zoom table position, or moving to the previous zoom table position.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | set_zoom_table_value | [[proto/cmd.HeatCamera.SetZoomTableValue]] | - |
| 2 | next_zoom_table_pos | [[proto/cmd.HeatCamera.NextZoomTablePos]] | - |
| 3 | prev_zoom_table_pos | [[proto/cmd.HeatCamera.PrevZoomTablePos]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3




## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider-with-presets
- **Feedback:** :fire-and-forget


### Purpose

Controls thermal camera optical zoom position


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.SetDigitalZoomLevel]]
- [[proto/cmd.HeatCamera.ResetZoom]]


### Preconditions

- Heat camera must be started




## Field Notes


### set_zoom_table_value (#1)

See [[proto/cmd.HeatCamera.SetZoomTableValue]]


### next_zoom_table_pos (#2)

Current zoom table position


### prev_zoom_table_pos (#3)

Current zoom table position



