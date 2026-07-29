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
| 0 | JON_GUI_DATA_VIDEO_CHANNEL_UNSPECIFIED | The proto3 zero — no channel selected, which in practice means the sender left the field unset. All five fields of this type in the proto surface (`cmd.CV.SetAutoFocus`, `cmd.CV.StartTrackNDC`, `cmd.CV.StartTrackTrinity`, `cmd.RotaryPlatform.RotateToNDC`, `cmd.RotaryPlatform.HaltWithNDC`) carry `not_in: [0]`, so it is never a legal wire value, and `uigen.resolve/enum-options` drops `_UNSPECIFIED` values from generated pickers. |
| 1 | JON_GUI_DATA_VIDEO_CHANNEL_HEAT | The thermal/IR camera channel: the command is routed to the thermal pipeline, and on the NDC-bearing commands this states that the accompanying x/y in [-1,1] are normalised coordinates of the HEAT frame — a different image with its own field of view, so the same pair means a different direction in the DAY frame. |
| 2 | JON_GUI_DATA_VIDEO_CHANNEL_DAY | The visible-light day camera channel: the command is routed to the day pipeline, and on the NDC-bearing commands it marks the accompanying [-1,1] x/y as normalised coordinates of the DAY frame. Consumers resolve the number from the enum rather than hardcoding it (`uigen.resolve/video-channel-value`). |

