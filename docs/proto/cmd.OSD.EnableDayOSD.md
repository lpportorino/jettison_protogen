---
id: cmd.OSD.EnableDayOSD
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# EnableDayOSD

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

Enables on-screen display overlay for day camera


### Related State

- [[proto/proto/ser.JonGuiDataOSD]]


### Related Commands

- [[proto/proto/cmd.OSD.DisableDayOSD]]


### Preconditions



### Implementation Notes

Uses jonOsdDisablePalette component. Simple toggle for OSD visibility.



