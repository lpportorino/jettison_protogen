---
id: cmd.HeatCamera.Zoom
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Zoom

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.SetDigitalZoomLevel]]
- [[proto/proto/cmd.HeatCamera.ResetZoom]]





## Field Notes


### set_zoom_table_value (#1)


#### Metadata

- **Semantic Type:** :count
- **Unit:** index
- **Precision:** 0



