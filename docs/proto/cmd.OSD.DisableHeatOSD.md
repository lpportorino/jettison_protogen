---
id: cmd.OSD.DisableHeatOSD
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# DisableHeatOSD

**Source:** `jon_shared_cmd_osd.proto`

## Description

Disables the thermal (heat) overlay display on the device's on-screen display (OSD), toggling off the heat map visualization that shows thermal imaging data.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Disable on-screen display overlay on heat camera video


### Related State

- [[proto/ser.JonGuiDataRecOsd#heat_osd_enabled]]


### Related Commands

- [[proto/cmd.OSD.EnableHeatOSD]]
- [[proto/cmd.OSD.DisableDayOSD]]





