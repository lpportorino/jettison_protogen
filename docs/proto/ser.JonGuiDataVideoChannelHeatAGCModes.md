---
id: ser.JonGuiDataVideoChannelHeatAGCModes
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataVideoChannelHeatAGCModes

**Source:** `jon_shared_data_types.proto`

## Description

Defines three Automatic Gain Control (AGC) modes for thermal camera operation: Mode 1 (mixed AGC), Mode 2 (auto AGC 1), and Mode 3 (auto AGC 2) that adjust image brightness and contrast for optimal thermal imaging visibility. Used throughout the system to configure thermal camera settings via the HeatCamera.SetAGC command.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_1 | AGC mode 1 |
| 2 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_2 | AGC mode 2 |
| 3 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_3 | AGC mode 3 |

