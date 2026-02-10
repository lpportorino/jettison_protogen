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
| 0 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_WHITE | Hot white polarity |
| 2 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_BLACK | Hot black polarity |
| 3 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA | Sepia color mapping |
| 4 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA_INVERSE | Inverted sepia color mapping |

