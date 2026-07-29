---
id: ser.JonGuiDataFxModeDay
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataFxModeDay

**Source:** `jon_shared_data_types.proto`

## Description

Represents selectable image processing presets for the day camera that optimize video quality for different environmental conditions. Includes DEFAULT plus six named presets (A-F) corresponding to scenarios like Daytime, Dusk, and Fog with distinct filter and processing algorithms.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_FX_MODE_DAY_DEFAULT | The camera's default image-processing preset, and the proto3 zero. Legal as REPORTED state — `JonGuiDataCameraDay.fx_mode` constrains only `defined_only` — but rejected as a COMMAND argument, because `cmd.DayCamera.SetFxMode` carries `not_in: [0]`; generated pickers therefore never offer it. |
| 1 | JON_GUI_DATA_FX_MODE_DAY_A | Preset A — the daytime preset: the predefined colour and exposure settings tuned for full daylight, per `cmd.DayCamera.SetFxMode`. Presets 1-6 are the values that command accepts, and `NextFxMode`/`PrevFxMode` step through the available list, wrapping at the end. |
| 2 | JON_GUI_DATA_FX_MODE_DAY_B | Preset B — the dusk preset, for the low, transitional light `cmd.DayCamera.SetFxMode` names as its second documented scenario. |
| 3 | JON_GUI_DATA_FX_MODE_DAY_C | Preset C — the fog preset, for the low-contrast atmospheric conditions named in `cmd.DayCamera.SetFxMode`. |
| 4 | JON_GUI_DATA_FX_MODE_DAY_D | Preset D — a further distinct day-camera processing preset. Only A, B and C carry a documented scenario, so what D targets is not established anywhere in this tree. |
| 5 | JON_GUI_DATA_FX_MODE_DAY_E | Preset E — a further distinct day-camera processing preset, with no scenario documented in the proto surface. |
| 6 | JON_GUI_DATA_FX_MODE_DAY_F | Preset F — a further distinct day-camera processing preset, with no scenario documented in the proto surface. |

