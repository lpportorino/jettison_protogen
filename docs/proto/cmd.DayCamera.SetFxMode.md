---
id: cmd.DayCamera.SetFxMode
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetFxMode

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the day camera's image processing FX mode to a specific preset. Each mode applies predefined color, contrast, and exposure settings optimized for different environmental lighting conditions to enhance video quality. The UI presents modes A-C with human-readable labels (Daytime, Dusk, Fog), while modes D-F are available for additional presets displayed as VF:IV through VF:VI.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | mode | [[proto/ser.JonGuiDataFxModeDay]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout (2s)


### Purpose

Explicitly set day camera FX mode by enum value; provides direct mode selection versus cycling with Next/PrevFxMode


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.NextFxMode]]
- [[proto/cmd.DayCamera.PrevFxMode]]
- [[proto/cmd.DayCamera.RefreshFxMode]]


### Preconditions

- Day camera must be started




## Field Notes


### mode (#1)

Target FX processing mode for the day camera video pipeline


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `{mode}`
- **Presets:** DAY_A (Daytime/VF:I), DAY_B (Dusk/VF:II), DAY_C (Fog/VF:III), DAY_D (VF:IV), DAY_E (VF:V), DAY_F (VF:VI)
- **Note:** DEFAULT (0) is excluded by validation constraint



