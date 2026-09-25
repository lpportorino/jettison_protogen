---
id: cmd.DayCamera.SceneAuto
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SceneAuto

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the operator's day FULL-AUTO latch, reported back as `JonGuiDataScene.day_auto`. While the classifier's `shadow` flag is true the latch changes nothing else: the guest keeps reporting what it would select and never issues a mode change.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | enable | bool | - |




## Field Notes


### enable (#1)

True to arm day full-auto scene selection, false to release it. Idempotent; the readback is `JonGuiDataScene.day_auto`.



