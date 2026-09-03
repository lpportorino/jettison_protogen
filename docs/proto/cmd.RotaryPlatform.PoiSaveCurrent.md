---
id: cmd.RotaryPlatform.PoiSaveCurrent
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# PoiSaveCurrent

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Stores the current pointing into point-of-interest slot `index`: the compensated azimuth and elevation the operator sees, plus both cameras' current zoom-table positions. Consumed by eutropia's drive host, which snapshots the state it publishes and POSTs the slot to poi_api_server (`POST /poi/{index}`), then refreshes the drive programs' POI table. No motion results from this command.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | index | int32 | >= 0, <= 9 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :number-input
- **Feedback:** :fire-and-forget


### Purpose

Save the current pointing and zoom tables as a point of interest



### Related Commands

- [[proto/cmd.RotaryPlatform.PoiLookAt]]





## Field Notes


### index (#1)

POI slot to write (0..9). An occupied slot is overwritten.


#### Metadata

- **Semantic Type:** :count



