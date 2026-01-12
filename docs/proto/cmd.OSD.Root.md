---
id: cmd.OSD.Root
proto: jon_shared_cmd_osd.proto
package: cmd.OSD
type: message
---

# Root

**Source:** `jon_shared_cmd_osd.proto`

## Description

Routes OSD (On-Screen Display) commands to control thermal camera display modes and elements, including switching between default/LRF measurement/LRF result screens and enabling/disabling thermal and visible imagery overlays.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | show_default_screen | [[proto/cmd.OSD.ShowDefaultScreen]] | - |
| 2 | show_lrf_measure_screen | [[proto/cmd.OSD.ShowLRFMeasureScreen]] | - |
| 3 | show_lrf_result_screen | [[proto/cmd.OSD.ShowLRFResultScreen]] | - |
| 4 | show_lrf_result_simplified_screen | [[proto/cmd.OSD.ShowLRFResultSimplifiedScreen]] | - |
| 5 | enable_heat_osd | [[proto/cmd.OSD.EnableHeatOSD]] | - |
| 6 | disable_heat_osd | [[proto/cmd.OSD.DisableHeatOSD]] | - |
| 7 | enable_day_osd | [[proto/cmd.OSD.EnableDayOSD]] | - |
| 8 | disable_day_osd | [[proto/cmd.OSD.DisableDayOSD]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8




## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :fire-and-forget


### Purpose

Controls OSD (on-screen display) visibility and screen modes



### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.OSD.ShowDefaultScreen]]
- [[proto/proto/proto/proto/proto/proto/cmd.OSD.EnableDayOSD]]
- [[proto/proto/proto/proto/proto/proto/cmd.OSD.DisableHeatOSD]]



### Implementation Notes

Container message for OSD configuration commands



