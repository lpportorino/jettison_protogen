---
id: cmd.Lrf_calib.Root
proto: jon_shared_cmd_lrf_align.proto
package: cmd.Lrf_calib
type: message
---

# Root

**Source:** `jon_shared_cmd_lrf_align.proto`

## Description

Calibrates laser rangefinder (LRF) crosshair alignment offsets for both day and thermal cameras, supporting operations to set, shift, save, or reset X/Y pixel offsets across different zoom levels.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | day | [[proto/cmd.Lrf_calib.Offsets]] | - |
| 2 | heat | [[proto/cmd.Lrf_calib.Offsets]] | - |


## Oneofs


### channel

Fields: #1, #2




## Interaction

- **Category:** :settings
- **UI Pattern:** :tabbed-config
- **Feedback:** :fire-and-forget


### Purpose

Root message container for LRF calibration commands


### Related State

- [[proto/ser.JonGuiDataLrf]]




### Implementation Notes

Calibration workflow for laser rangefinder alignment



## Field Notes


### day (#1)

See [[proto/cmd.Lrf_calib.Offsets]]


### heat (#2)

See [[proto/cmd.Lrf_calib.Offsets]]



