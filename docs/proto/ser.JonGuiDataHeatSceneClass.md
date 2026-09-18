---
id: ser.JonGuiDataHeatSceneClass
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataHeatSceneClass

**Source:** `jon_shared_data_types.proto`

## Description

The scene classes the FULL-AUTO thermal classifier can hold or propose (`JonGuiDataScene.heat_class` / `heat_challenger`). The classifier needs a PRE-AGC thermal signal that no box publishes today, so every box reports `UNSPECIFIED` with reason `noinput`; the enum exists so the wire is ready for the day that telemetry lands.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_HEAT_SCENE_CLASS_UNSPECIFIED | No thermal class held — the expected state while the pre-AGC input is unfed. Never scored; the proto3 zero. |
| 1 | JON_GUI_DATA_HEAT_SCENE_CLASS_HIGH_CONTRAST | A thermal scene with a wide pre-AGC radiometric spread (hot targets against a cool background). Maps to the high-contrast heat preset. |
| 2 | JON_GUI_DATA_HEAT_SCENE_CLASS_LOW_CONTRAST | A thermal scene with a narrow pre-AGC spread (uniform temperatures, fog, rain). Maps to the low-contrast heat preset. |

