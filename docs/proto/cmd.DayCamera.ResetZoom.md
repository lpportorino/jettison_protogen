---
id: cmd.DayCamera.ResetZoom
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# ResetZoom

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Resets the day camera's optical zoom to its default position (typically 1x or minimum zoom). This fire-and-forget command is triggered via an action button in the UI zoom control panel and requires the day camera to be started before execution.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Resets day camera zoom to default position


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/proto/proto/proto/cmd.DayCamera.SetZoomTableValue]]


### Preconditions

- Day camera must be started




