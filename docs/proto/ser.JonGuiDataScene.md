---
id: ser.JonGuiDataScene
proto: jon_shared_data_scene.proto
package: ser
type: message
---

# JonGuiDataScene

**Source:** `jon_shared_data_scene.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | day_auto | bool | - |
| 2 | day_class | [[proto/ser.JonGuiDataSceneClass]] | defined enum value only |
| 3 | day_challenger | [[proto/ser.JonGuiDataSceneClass]] | defined enum value only |
| 4 | day_hold_s | uint32 | - |
| 5 | day_hold_reason | string | max-len: 15 |
| 6 | day_mode | [[proto/ser.JonGuiDataFxModeDay]] | defined enum value only |
| 7 | day_scores | repeated float | max-items: 5, each float: >= 0, <= 1 |
| 8 | heat_auto | bool | - |
| 9 | heat_class | [[proto/ser.JonGuiDataHeatSceneClass]] | defined enum value only |
| 10 | heat_challenger | [[proto/ser.JonGuiDataHeatSceneClass]] | defined enum value only |
| 11 | heat_hold_s | uint32 | - |
| 12 | heat_hold_reason | string | max-len: 15 |
| 13 | heat_mode | [[proto/ser.JonGuiDataFxModeHeat]] | defined enum value only |
| 14 | heat_scores | repeated float | max-items: 2, each float: >= 0, <= 1 |
| 15 | shadow | bool | - |




