---
id: cmd.DayCamera.SetFxMode
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetFxMode

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | mode | [[proto/ser.JonGuiDataFxModeDay]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout


### Purpose

Set day camera image processing FX mode preset (daytime/dusk/fog)


### Related State

- [[proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/cmd.DayCamera.NextFxMode]]
- [[proto/proto/cmd.DayCamera.PrevFxMode]]
- [[proto/proto/cmd.DayCamera.RefreshFxMode]]


### Preconditions

- Day camera must be started




## Field Notes


### mode (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `{mode}`
- **Presets:** DAY_A, DAY_B, DAY_C



