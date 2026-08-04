---
id: cmd.DayCamera.SetFxMode
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetFxMode

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the day camera's image processing FX mode to a specific preset (DAY_A for daytime, DAY_B for dusk, or DAY_C for fog conditions). Each mode applies predefined color and exposure settings optimized for different environmental lighting conditions to enhance video quality.

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

- [[proto/ser.JonGuiDataCameraDay#fx_mode]]


### Related Commands

- [[proto/cmd.DayCamera.NextFxMode]]
- [[proto/cmd.DayCamera.PrevFxMode]]
- [[proto/cmd.DayCamera.RefreshFxMode]]


### Preconditions

- Day camera must be started




## Field Notes


### mode (#1)

Operating mode


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `{mode}`
- **Presets:** DAY_A, DAY_B, DAY_C



