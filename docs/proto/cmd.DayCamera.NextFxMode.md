---
id: cmd.DayCamera.NextFxMode
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# NextFxMode

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.PrevFxMode]]
- [[proto/proto/cmd.DayCamera.SetFxMode]]


### Preconditions

- Day camera must be started




