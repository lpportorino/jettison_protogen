---
id: cmd.Lrf.EnableFogMode
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# EnableFogMode

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Enables fog mode on the LRF (Laser Range Finder) device for improved range finding performance in foggy or adverse weather conditions using low visible wavelength measurement.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Enable fog mode for laser range finder (optimizes for low visibility)


### Related State

- [[proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/cmd.Lrf.DisableFogMode]]
- [[proto/cmd.Lrf.Start]]


### Preconditions

- LRF must be started


### Implementation Notes

Fog mode toggle is exposed in the frontend via `toggleFogMode()` hotkey command (L+F). The state is tracked in `ser.JonGuiDataLrf.fogModeEnabled` boolean field. Fog mode uses a lower visibility wavelength optimized for adverse weather conditions (fog, rain, snow), while standard mode uses higher visibility wavelength for clear atmospheric conditions.



