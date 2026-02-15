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

- [[proto/ser.JonGuiDataRecOsd]]


### Related Commands

- [[proto/cmd.OSD.EnableDayOSD]]



### Preconditions

- Day camera video must be active


### Implementation Notes

Frontend function `OSDDisableDayOSD()` in `cmdOSD.ts` sends this command. Forms a toggle pair with EnableDayOSD. The OSD overlay renders telemetry information (crosshair, compass, GPS, altitude) onto the day camera video stream. Controlled via `jonOsdDisablePalette` component.



