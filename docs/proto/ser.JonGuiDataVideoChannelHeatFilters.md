---
id: ser.JonGuiDataVideoChannelHeatFilters
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataVideoChannelHeatFilters

**Source:** `jon_shared_data_types.proto`

## Description

Specifies thermal camera display color schemes with four filter modes: Hot White (hottest objects rendered in white), Hot Black (hottest objects rendered in black), Sepia (warm tone colorization), and Sepia Inverse (inverted warm tone colorization). Applied via HeatCamera.SetFilters to control how thermal image data is visualized in real-time.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_UNSPECIFIED | The proto3 zero — no filter selected. Both `JonGuiDataCameraHeat.filter` (state) and `cmd.HeatCamera.SetFilters` (command) carry `not_in: [0]`, so it never appears on a valid message; seeing it means the field was left unset. |
| 1 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_WHITE | White-hot polarity — the greyscale ramp renders the hottest scene content white and the coldest black. This is a display mapping only: it changes which end of the thermal intensity range appears bright, never the underlying measurement. |
| 2 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_BLACK | Black-hot polarity — the same greyscale ramp inverted, so the hottest content renders black and the coldest white. |
| 3 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA | Sepia false-colour mapping — the thermal intensity ramp rendered in warm tones rather than neutral grey. |
| 4 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA_INVERSE | The sepia mapping inverted, so the intensity-to-colour assignment runs the opposite way along the same warm-tone ramp. |

