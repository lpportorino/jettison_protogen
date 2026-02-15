---
id: cmd.HeatCamera.ZoomStop
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# ZoomStop

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Stops the thermal camera zoom motion in progress, sent when the zoom button is released after a zoom in or out command. Used in press-release input patterns for analog zoom control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops continuous zoom movement on heat camera


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.ZoomIn]]
- [[proto/cmd.HeatCamera.ZoomOut]]


### Preconditions

- Heat camera started


### Implementation Notes

Used with continuous zoom in/out commands



