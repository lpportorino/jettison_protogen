---
id: cmd.CV.StopTrack
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# StopTrack

**Source:** `jon_shared_cmd_cv.proto`

## Description

Stops active video tracking on both day and thermal cameras. When sent from the frontend, it is forwarded to both pipeline command channels to terminate automatic target following. The tracking button (with corner brackets icon) only appears when system.tracking is true.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops active video tracking



### Related Commands

- [[proto/cmd.CV.StartTrackNDC]]



### Implementation Notes

Frontend implementation:
- **UI Component**: `jonTrackingButton.ts` - A dedicated stop tracking button that appears when `system.tracking` is true
- **Icon**: Corner brackets with smiley face (tracking target indicator)
- **Visibility**: Button is hidden when tracking is inactive, appears dynamically when tracking starts
- **Hotkey**: Available via `hotkeyCommands.ts` as `stopTrack()`
- **Tooltip**: "Stop Target Tracking - ends automatic target following"

The command sends `{ cv: { stopTrack: {} } }` via the WebSocket command channel



