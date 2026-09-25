---
id: ser.JonGuiDataSceneClass
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataSceneClass

**Source:** `jon_shared_data_types.proto`

## Description

The scene classes the FULL-AUTO day classifier can hold or propose (`JonGuiDataScene.day_class` / `day_challenger`). Each maps onto one existing day FX mode in the guest (`mods/isp3a/scene/scene_classes.h`), which is the only place a class number is decided; a host test pins these values to that header.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_SCENE_CLASS_UNSPECIFIED | No class held — the guest has no usable input (`day_hold_reason` says why) or has not decided yet. Never scored; the proto3 zero. |
| 1 | JON_GUI_DATA_SCENE_CLASS_DAY | Full daylight: the AE ladder well below its light-starved region and a neutral-to-cool white balance. Maps to the day preset the guest assigns to DAY. |
| 2 | JON_GUI_DATA_SCENE_CLASS_DUSK | Falling light: the ladder climbing toward its ceiling (exposure and gain rising, iris opening) with a warm white balance. Maps to the dusk preset. |
| 3 | JON_GUI_DATA_SCENE_CLASS_NIGHT | Light-starved: the ladder at or near its ceiling (gain railed, exposure at its maximum). Maps to the night preset. |
| 4 | JON_GUI_DATA_SCENE_CLASS_FOG | Low-contrast bright scene: a flat luma histogram at daylight exposure. Maps to the fog/dehaze preset. |
| 5 | JON_GUI_DATA_SCENE_CLASS_OVERCAST | Diffuse daylight: daylight exposure with a cooler white balance and a compressed dynamic range short of the fog case. Maps to the overcast preset. |

