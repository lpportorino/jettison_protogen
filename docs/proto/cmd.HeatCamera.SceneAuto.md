---
id: cmd.HeatCamera.SceneAuto
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SceneAuto

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Sets the operator's heat FULL-AUTO latch, reported back as `JonGuiDataScene.heat_auto`. While the classifier's `shadow` flag is true the latch changes nothing else.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | enable | bool | - |




## Field Notes


### enable (#1)

True to arm heat full-auto scene selection, false to release it. Idempotent; the readback is `JonGuiDataScene.heat_auto`.



