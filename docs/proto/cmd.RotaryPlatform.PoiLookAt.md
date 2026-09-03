---
id: cmd.RotaryPlatform.PoiLookAt
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# PoiLookAt

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Slews the rotary platform to the point of interest stored in slot `index` and applies that slot's day and heat zoom-table positions. The command is consumed by eutropia's drive host, which runs the sandboxed POI program: it verifies arrival by encoder readback (compensated frame, the same frame the slot was saved in), applies a per-leg deadline, and refuses to start while the platform is not started, is parked, or any drive exclusion (compass calibration, geodesic mode, active tracking or zoom-to-ROI) is in effect. The slot contents are read from poi_api_server (`GET /poi/{index}`); an empty slot is a fault, not a no-op.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | index | int32 | >= 0, <= 9 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :number-input
- **Feedback:** :poll-confirm


### Purpose

Slew to a stored point of interest with verified arrival


### Related State

- [[proto/ser.JonGuiDataDrive#poi_index]]
- [[proto/ser.JonGuiDataRotary#platform_azimuth]]


### Related Commands

- [[proto/cmd.RotaryPlatform.PoiSaveCurrent]]
- [[proto/cmd.RotaryPlatform.Halt]]





## Field Notes


### index (#1)

POI slot to look at (0..9).


#### Metadata

- **Semantic Type:** :count



