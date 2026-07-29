---
id: ser.JonGuiDataRecOsdScreen
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataRecOsdScreen

**Source:** `jon_shared_data_types.proto`

## Description

Specifies which OSD (On-Screen Display) overlay screen to display during recording: MAIN (default interface), LRF_MEASURE (laser rangefinder measurement input), LRF_RESULT (full rangefinder results), or LRF_RESULT_SIMPLIFIED (condensed results).

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_REC_OSD_SCREEN_UNSPECIFIED | The proto3 zero — no screen reported. `JonGuiDataRecOsd.screen` carries `not_in: [0]`, so a valid state message never carries it; it means the field was left unset. |
| 1 | JON_GUI_DATA_REC_OSD_SCREEN_MAIN | The default OSD home screen — the state reported after `cmd.OSD.ShowDefaultScreen`, which is documented as triggered by the gamepad exit button or a keyboard hotkey. This OSD is the device-side overlay composited onto the recorded video, which is why the same message also carries the per-channel crosshair offsets. |
| 2 | JON_GUI_DATA_REC_OSD_SCREEN_LRF_MEASURE | The laser-rangefinder measurement screen, shown while a measurement is being initiated — the state after `cmd.OSD.ShowLRFMeasureScreen`, documented as typically triggered by the gamepad measure button. |
| 3 | JON_GUI_DATA_REC_OSD_SCREEN_LRF_RESULT | The full rangefinder result screen, showing distance measurement data and the targeting overlay — the state after `cmd.OSD.ShowLRFResultScreen`. |
| 4 | JON_GUI_DATA_REC_OSD_SCREEN_LRF_RESULT_SIMPLIFIED | The compact result overlay used during continuous LRF scanning — the state after `cmd.OSD.ShowLRFResultSimplifiedScreen`, documented as triggered by a long press of the measure button. |

