---
id: ser.JonGuiDataFxModeHeat
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataFxModeHeat

**Source:** `jon_shared_data_types.proto`

## Description

Defines available special effects (FX) modes for the thermal camera that control how the thermal image is processed and displayed. Includes DEFAULT plus six modes (A-F) representing different hardware-level or DSP-level image processing algorithms.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_FX_MODE_HEAT_DEFAULT | The thermal camera's default image-processing preset, and the proto3 zero. Legal as REPORTED state — `JonGuiDataCameraHeat.fx_mode` constrains only `defined_only` — but rejected as a COMMAND argument, because `cmd.HeatCamera.SetFxMode` carries `not_in: [0]`; generated pickers therefore never offer it. |
| 1 | JON_GUI_DATA_FX_MODE_HEAT_A | Thermal FX preset A — one of the six distinct enhancement presets `cmd.HeatCamera.SetFxMode` accepts and `NextFxMode`/`PrevFxMode` cycle through, wrapping at the end. The proto surface documents the presets only as differing hardware- or DSP-level processing; it does not say what A applies. |
| 2 | JON_GUI_DATA_FX_MODE_HEAT_B | Thermal FX preset B — a distinct enhancement preset; as with every letter in this enum, the processing it applies is not documented in the proto surface. |
| 3 | JON_GUI_DATA_FX_MODE_HEAT_C | Thermal FX preset C — a distinct enhancement preset, undocumented in the proto surface beyond being separately selectable. |
| 4 | JON_GUI_DATA_FX_MODE_HEAT_D | Thermal FX preset D — a distinct enhancement preset, undocumented in the proto surface beyond being separately selectable. |
| 5 | JON_GUI_DATA_FX_MODE_HEAT_E | Thermal FX preset E — a distinct enhancement preset, undocumented in the proto surface beyond being separately selectable. |
| 6 | JON_GUI_DATA_FX_MODE_HEAT_F | Thermal FX preset F — a distinct enhancement preset, undocumented in the proto surface beyond being separately selectable. |

