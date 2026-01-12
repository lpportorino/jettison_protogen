---
id: cmd.OSD.ShowLRFResultSimplifiedScreen
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# ShowLRFResultSimplifiedScreen

**Source:** `jon_shared_cmd_osd.proto`

## Description

Displays a simplified laser rangefinder result screen for continuous scanning mode, triggered after a long press of the measure button to show results in a compact overlay format during active LRF scanning.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Switch OSD to simplified LRF result display screen


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataOSD]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.OSD.ShowDefaultScreen]]
- [[proto/proto/proto/proto/proto/proto/cmd.OSD.ShowLRFMeasureScreen]]
- [[proto/proto/proto/proto/proto/proto/cmd.OSD.ShowLRFResultScreen]]





