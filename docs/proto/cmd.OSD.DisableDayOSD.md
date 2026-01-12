---
id: cmd.OSD.DisableDayOSD
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# DisableDayOSD

**Source:** `jon_shared_cmd_osd.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Disables on-screen display overlay on day camera video


### Related State

- [[proto/proto/ser.JonGuiDataOsd]]


### Related Commands

- [[proto/proto/cmd.OSD.EnableDayOSD]]



### Implementation Notes

Removes telemetry overlay from video output



