---
id: cmd.Lrf.DisableFogMode
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# DisableFogMode

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Disables fog mode for LRF distance measurement, causing the laser rangefinder to revert to standard high-visibility measurement when taking distance readings.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Disable LRF fog mode for normal atmospheric conditions


### Related State

- [[proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/cmd.Lrf.EnableFogMode]]
- [[proto/cmd.Lrf.Start]]


### Preconditions

- LRF must be started


### Implementation Notes

Fog mode toggle is exposed in the frontend via `toggleFogMode()` hotkey command (L+F). The state is tracked in `ser.JonGuiDataLrf.fogModeEnabled` boolean field. Standard measurement mode uses higher visibility wavelength, while fog mode uses lower visibility wavelength optimized for adverse weather.




