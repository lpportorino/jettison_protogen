---
id: cmd.DayCamera.RefreshFxMode
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# RefreshFxMode

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Trigger command that requests the day camera to re-apply its current FX mode settings. This parameterless command is useful for refreshing visual effects after parameter changes or to ensure the current mode is properly active without cycling to a different mode.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Refresh current FX mode on day camera


### Related State

- [[proto/proto/proto/ser.JonGuiDataCameraDay]]




### Implementation Notes

Empty message - trigger only



