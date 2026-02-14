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

Root message container for LRF calibration commands. This top-level message wraps all laser rangefinder crosshair alignment operations, routing them to either the day or thermal camera channel via the `channel` oneof.


### Related State

- [[proto/ser.JonGuiDataRecOsd]] - Exposes `dayCrosshairOffsetHorizontal`, `dayCrosshairOffsetVertical`, `heatCrosshairOffsetHorizontal`, `heatCrosshairOffsetVertical` fields reflecting current crosshair positions
- [[proto/ser.JonGuiDataLrf]] - LRF operational state including measurement status


### Related Commands

- [[proto/cmd.Lrf_calib.SetOffsets]] - Set absolute X/Y pixel offsets
- [[proto/cmd.Lrf_calib.ShiftOffsetsBy]] - Incrementally adjust offsets
- [[proto/cmd.Lrf_calib.SaveOffsets]] - Persist offsets to storage
- [[proto/cmd.Lrf_calib.ResetOffsets]] - Revert to saved offsets


### Preconditions

- Target camera (day or heat) must be started
- LRF module must be initialized


### Implementation Notes

The frontend implements two separate crosshair mover panels (`jonDayCrosshairMover` and `jonHeatCrosshairMover` Lit components) that provide independent calibration controls for each camera channel. Each panel displays the current crosshair offset values from `ser.JonGuiDataRecOsd` and offers:

- **Directional steppers**: Fine (+/-1 pixel) and coarse (+/-10 pixel) adjustments via `ShiftOffsetsBy`
- **Save button**: Persists current offsets to Redis via `SaveOffsets`
- **Reset button**: Reverts to last saved offsets via `ResetOffsets`

Commands are sent via WebSocket using the `cmdLRFAlignment.ts` module, which wraps the message hierarchy: `cmd.Root.lrf_calib` -> `cmd.Lrf_calib.Root.day/heat` -> `cmd.Lrf_calib.Offsets.set/shift/save/reset`.

Offsets are stored per zoom level to account for optical parallax at different magnifications. The UI monitors state changes with a 2-second pending timeout for visual feedback.



## Field Notes


### day (#1)

Calibration offsets for the day camera channel. Wraps a `cmd.Lrf_calib.Offsets` message containing one of `set`, `shift`, `save`, or `reset` operations. The day camera resolution is 1920x1080, so offset constraints allow the full frame range. Current offset values are reflected in `ser.JonGuiDataRecOsd.dayCrosshairOffsetHorizontal` and `ser.JonGuiDataRecOsd.dayCrosshairOffsetVertical`.

See [[proto/cmd.Lrf_calib.Offsets]]


### heat (#2)

Calibration offsets for the thermal camera channel. Wraps a `cmd.Lrf_calib.Offsets` message containing one of `set`, `shift`, `save`, or `reset` operations. The thermal camera resolution is 900x720, though offset constraints use the day camera range for consistency. Current offset values are reflected in `ser.JonGuiDataRecOsd.heatCrosshairOffsetHorizontal` and `ser.JonGuiDataRecOsd.heatCrosshairOffsetVertical`.

See [[proto/cmd.Lrf_calib.Offsets]]



