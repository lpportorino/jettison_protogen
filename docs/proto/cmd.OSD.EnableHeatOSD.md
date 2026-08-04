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

- [[proto/ser.JonGuiDataRecOsd#heat_osd_enabled]]


### Related Commands

- [[proto/cmd.OSD.DisableHeatOSD]]



### Implementation Notes

Empty message - trigger only



