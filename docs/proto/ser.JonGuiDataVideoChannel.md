---
id: ser.JonGuiDataVideoChannel
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataVideoChannel

**Source:** `jon_shared_data_types.proto`

## Description

Specifies the active video source with two primary channels: thermal imaging (HEAT) and visible light (DAY). Used throughout command messages and UI components to route camera control operations and render channel-specific overlays to their respective video pipelines.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_VIDEO_CHANNEL_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT | Thermal/IR camera |
| 2 | JON_GUI_DATA_VIDEO_CHANNEL_DAY | Day/visible camera |

