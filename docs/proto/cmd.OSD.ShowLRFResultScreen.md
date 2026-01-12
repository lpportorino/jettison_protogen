---
id: cmd.OSD.ShowLRFResultScreen
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# ShowLRFResultScreen

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

Switch OSD to laser rangefinder result display screen


### Related State

- [[proto/proto/ser.JonGuiDataOSD]]
- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.OSD.ShowDefaultScreen]]
- [[proto/proto/cmd.OSD.ShowLRFMeasureScreen]]



### Implementation Notes

Empty message - trigger only



