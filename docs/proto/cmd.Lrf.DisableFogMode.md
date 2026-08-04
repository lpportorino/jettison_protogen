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
- **Feedback:** :pending-timeout


### Purpose

Disable LRF fog mode for normal atmospheric conditions


### Related State

- [[proto/ser.JonGuiDataLrf#fogModeEnabled]]


### Related Commands

- [[proto/cmd.Lrf.EnableFogMode]]
- [[proto/cmd.Lrf.Start]]


### Preconditions

- LRF must be started




