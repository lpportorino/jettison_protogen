---
id: cmd.DayCamera.SaveToTable
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SaveToTable

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Saves the current day camera optical zoom position to a zoom lookup table for later recall. This parameterless command is triggered via a fire-and-forget action button in the UI, enabling users to quickly restore frequently-used zoom positions during camera operation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Saves current day camera zoom position to zoom table


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.ResetZoom]]
- [[proto/cmd.DayCamera.SetZoomTableValue]]
- [[proto/cmd.DayCamera.NextZoomTablePos]]
- [[proto/cmd.DayCamera.PrevZoomTablePos]]
- [[proto/cmd.DayCamera.SaveToTableFocus]]


### Preconditions

- Day camera must be started
- Valid zoom position




