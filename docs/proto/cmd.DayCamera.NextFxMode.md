---
id: cmd.DayCamera.NextFxMode
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# NextFxMode

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Cycles to the next FX (visual effects) mode for the day camera. FX modes include image enhancement filters like CLAHE, edge detection, and color adjustments. This parameterless command advances through the available modes list, wrapping around at the end.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Cycles to next FX mode for day camera


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.PrevFxMode]]
- [[proto/cmd.DayCamera.SetFxMode]]


### Preconditions

- Day camera must be started




