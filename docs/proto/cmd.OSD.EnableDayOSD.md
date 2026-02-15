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

- [[proto/ser.JonGuiDataRecOsd]]


### Related Commands

- [[proto/cmd.OSD.DisableDayOSD]]


### Preconditions

- Day camera video must be active


### Implementation Notes

Frontend function `OSDEnableDayOSD()` in `cmdOSD.ts` sends this command. Forms a toggle pair with DisableDayOSD. Controlled via `jonOsdDisablePalette` component toggle button. The OSD overlay renders crosshair, navball, timestamps, and telemetry widgets onto the day camera video stream.



