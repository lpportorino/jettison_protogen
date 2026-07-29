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
| 0 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_UNSPECIFIED | The proto3 zero — no AGC mode set. Forbidden in both directions: `JonGuiDataCameraHeat.agc_mode` (state) and `cmd.HeatCamera.SetAGC` (command) both carry `not_in: [0]`, so a valid message never carries it and its presence means the field was left unset. |
| 1 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_1 | AGC preset 1. Automatic Gain Control maps the sensor's raw thermal intensity onto the display's brightness and contrast range, and these three values select between device presets for that mapping; this enum's description characterises preset 1 as "mixed" AGC, and the proto surface documents nothing further about the algorithm. |
| 2 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_2 | AGC preset 2 — described by this enum as the first of the two automatic AGC presets ("auto AGC 1"). What distinguishes it from preset 3 is not documented in the proto surface. |
| 3 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_3 | AGC preset 3 — described by this enum as the second automatic AGC preset ("auto AGC 2"). What distinguishes it from preset 2 is not documented in the proto surface. |

