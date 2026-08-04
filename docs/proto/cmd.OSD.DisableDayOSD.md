---
id: cmd.OSD.DisableDayOSD
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# DisableDayOSD

**Source:** `jon_shared_cmd_osd.proto`

## Description

Sends a command to disable the day-mode on-screen display (OSD) on the device, toggling off the day camera telemetry overlay by sending an empty DisableDayOSD message to the command server.

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

- [[proto/ser.JonGuiDataRecOsd#day_osd_enabled]]


### Related Commands

- [[proto/cmd.OSD.EnableDayOSD]]



### Implementation Notes

Removes telemetry overlay from video output



