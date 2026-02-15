---
id: ser.JonGuiDataRecOsd
proto: jon_shared_data_rec_osd.proto
package: ser
type: message
---

# JonGuiDataRecOsd

**Source:** `jon_shared_data_rec_osd.proto`

## Description

Represents the recording on-screen display (OSD) configuration state, tracking whether thermal and day camera overlays are enabled, along with their respective crosshair offset positions for proper alignment on recorded frames.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | screen | [[proto/ser.JonGuiDataRecOsdScreen]] | defined enum value only, not in: 0 |
| 2 | heat_osd_enabled | bool | - |
| 3 | day_osd_enabled | bool | - |
| 4 | heat_crosshair_offset_horizontal | int32 | - |
| 5 | heat_crosshair_offset_vertical | int32 | - |
| 6 | day_crosshair_offset_horizontal | int32 | - |
| 7 | day_crosshair_offset_vertical | int32 | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Recording and on-screen display configuration state





### Implementation Notes

Displays current OSD screen mode and crosshair offsets for both cameras



## Field Notes


### screen (#1)

See related enum for valid values


#### Metadata

- **Semantic Type:** :enum-label


### heat_osd_enabled (#2)

Heat camera OSD enabled state


#### Metadata

- **Semantic Type:** :toggle-state


### day_osd_enabled (#3)

Day camera OSD enabled state


#### Metadata

- **Semantic Type:** :toggle-state


### heat_crosshair_offset_horizontal (#4)

Crosshair position offset


#### Metadata

- **Semantic Type:** :raw
- **Unit:** px


### heat_crosshair_offset_vertical (#5)

Crosshair position offset


#### Metadata

- **Semantic Type:** :raw
- **Unit:** px


### day_crosshair_offset_horizontal (#6)

Crosshair position offset


#### Metadata

- **Semantic Type:** :raw
- **Unit:** px


### day_crosshair_offset_vertical (#7)

Crosshair position offset


#### Metadata

- **Semantic Type:** :raw
- **Unit:** px



