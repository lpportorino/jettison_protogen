---
id: cmd.OSD.EnableHeatOSD
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# EnableHeatOSD

**Source:** `jon_shared_cmd_osd.proto`

## Description

Enables the thermal (heat) On-Screen Display (OSD) overlay on the video stream, allowing the user to toggle thermal imaging visualization on or off.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Enable on-screen display overlay for heat camera


### Related State

- [[proto/ser.JonGuiDataRecOsd]]


### Related Commands

- [[proto/cmd.OSD.DisableHeatOSD]]



### Preconditions

- Heat camera video must be active


### Implementation Notes

Frontend function `OSDEnableHeatOSD()` in `cmdOSD.ts` sends this command. Forms a toggle pair with DisableHeatOSD. The OSD overlay renders thermal-specific telemetry onto the heat camera video stream.



