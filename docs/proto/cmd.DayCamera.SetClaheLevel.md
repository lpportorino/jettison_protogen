---
id: cmd.DayCamera.SetClaheLevel
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetClaheLevel

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :slider
- **Feedback:** :pending-timeout


### Purpose

Set CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level for day camera


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.ShiftClaheLevel]]


### Preconditions

- Day camera must be started




## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`
- **Presets:** 0.0, 0.25, 0.5, 0.75, 1.0



