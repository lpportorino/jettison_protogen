---
id: cmd.HeatCamera.SetZoomTableValue
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetZoomTableValue

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the thermal camera optical zoom to a specific discrete table position (typically 0-4). Used for zoom table navigation in POI scanning, hotkey-based zoom control, and synchronized zoom operations with the day camera.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | int32 | >= 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Sets thermal camera zoom to specific table position (discrete zoom level)


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.NextZoomTablePos]]
- [[proto/proto/cmd.HeatCamera.PrevZoomTablePos]]
- [[proto/proto/cmd.DayCamera.SetZoomTableValue]]


### Preconditions

- Camera must be started


### Implementation Notes

Used extensively for POI navigation, scan nodes, and hotkey-based zoom control. Typically values 0-4. Often synchronized with day camera zoom.



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :count
- **Unit:** table index
- **Precision:** 0



