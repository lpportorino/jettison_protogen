---
id: cmd.OSD.ShowLRFMeasureScreen
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# ShowLRFMeasureScreen

**Source:** `jon_shared_cmd_osd.proto`

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

Switch OSD to laser rangefinder measurement screen


### Related State

- [[proto/proto/ser.JonGuiDataOSD]]
- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.OSD.ShowDefaultScreen]]
- [[proto/proto/cmd.OSD.ShowLRFResultScreen]]



### Implementation Notes

Empty message - trigger only



