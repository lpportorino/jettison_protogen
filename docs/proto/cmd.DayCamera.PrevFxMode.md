---
id: cmd.DayCamera.PrevFxMode
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# PrevFxMode

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Cycles to the previous visual effect (FX) mode for the day camera. Counterpart to NextFxMode, this command navigates backward through the available FX modes list. Used in keyboard shortcuts and FX mode selector buttons for bidirectional mode navigation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Cycle to previous FX mode (visual effect) for day camera


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.NextFxMode]]
- [[proto/cmd.DayCamera.SetFxMode]]



### Implementation Notes

Cycles through FX modes in reverse order, used in keyboard shortcuts and FX mode selector button



