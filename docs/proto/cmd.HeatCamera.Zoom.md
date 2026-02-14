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
- [[proto/cmd.DayCamera.Zoom]]


### Preconditions

- Heat camera must be started


### Implementation Notes

This container message provides three mutually exclusive ways to control optical zoom via its `cmd` oneof:

1. **set_zoom_table_value** - Jump directly to a specific zoom table position (typically 0-4)
2. **next_zoom_table_pos** - Step forward one position in the zoom table
3. **prev_zoom_table_pos** - Step backward one position in the zoom table

**Frontend Usage:**

In `jonZoomUi.ts`, this command is used for:
- Slider-based zoom control in the zoom palette UI
- Synchronized zoom when `isSynced` signal is enabled (both day and heat cameras zoom together)

In `hotkeyCommands.ts`, this command supports:
- Direct position hotkeys (Numpad 0-4 for zoom positions)
- Incremental zoom hotkeys (scroll-based next/prev)

In `poiCommands.ts`, this command is used when navigating to POIs (Points of Interest), restoring the saved thermal zoom position.

In `interactionHandler.ts`, pinch gestures and mouse wheel events trigger next/prev zoom table position commands.

The current zoom position is reflected in `ser.JonGuiDataCameraHeat.zoomTablePos` with maximum position in `zoomTablePosMax`.





## Field Notes


### set_zoom_table_value (#1)

See [[proto/cmd.HeatCamera.SetZoomTableValue]]


### next_zoom_table_pos (#2)

Advances to the next position in the zoom table. Empty message (trigger only) - no parameters required. See [[proto/cmd.HeatCamera.NextZoomTablePos]].


### prev_zoom_table_pos (#3)

Moves to the previous position in the zoom table. Empty message (trigger only) - no parameters required. See [[proto/cmd.HeatCamera.PrevZoomTablePos]].



