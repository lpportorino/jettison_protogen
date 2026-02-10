---
id: cmd.OSD.EnableDayOSD
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# EnableDayOSD

**Source:** `jon_shared_cmd_osd.proto`

## Description

Enables the day mode on-screen display (OSD) on the device, activating day vision overlay information in the visual feed.

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

- [[proto/ser.JonGuiDataOSD]]


### Related Commands

- [[proto/cmd.OSD.DisableDayOSD]]


### Preconditions



### Implementation Notes

Uses jonOsdDisablePalette component. Simple toggle for OSD visibility.



