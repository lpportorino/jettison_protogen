---
id: cmd.OSD.ShowDefaultScreen
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# ShowDefaultScreen

**Source:** `jon_shared_cmd_osd.proto`

## Description

Instructs the device to display the default OSD home screen, typically triggered by gamepad exit button or keyboard hotkey.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Switch OSD to default/home screen layout


### Related State

- [[proto/ser.JonGuiDataRecOsd]]


### Related Commands

- [[proto/cmd.OSD.ShowLRFMeasureScreen]]
- [[proto/cmd.OSD.ShowLRFResultScreen]]



### Implementation Notes

Empty message - trigger only



