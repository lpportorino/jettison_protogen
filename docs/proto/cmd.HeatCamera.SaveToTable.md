---
id: cmd.HeatCamera.SaveToTable
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SaveToTable

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Saves the current thermal camera zoom position to a lookup table for quick recall. This parameterless fire-and-forget trigger allows users to store frequently-used zoom positions for later retrieval via zoom table navigation commands.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings <!-- Persists zoom configuration to lookup table -->
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Save current zoom position to lookup table for quick recall. Used in conjunction with zoom table navigation commands to store frequently-used zoom positions.


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.Zoom]]
- [[proto/cmd.HeatCamera.NextZoomTablePos]]
- [[proto/cmd.HeatCamera.PrevZoomTablePos]]
- [[proto/cmd.HeatCamera.SetZoomTableValue]]
- [[proto/cmd.HeatCamera.ResetZoom]]



### Implementation Notes

Empty message - trigger only



