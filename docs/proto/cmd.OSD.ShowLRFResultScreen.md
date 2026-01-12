---
id: cmd.OSD.ShowLRFResultScreen
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# ShowLRFResultScreen

**Source:** `jon_shared_cmd_osd.proto`

## Description

Commands the device to display the laser rangefinder (LRF) measurement results on the on-screen display (OSD), switching from the default view to show distance measurement data and targeting overlay.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Switch OSD to laser rangefinder result display screen


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataOSD]]
- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.OSD.ShowDefaultScreen]]
- [[proto/proto/proto/proto/proto/proto/cmd.OSD.ShowLRFMeasureScreen]]



### Implementation Notes

Empty message - trigger only



