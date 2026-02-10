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





