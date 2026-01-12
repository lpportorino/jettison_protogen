---
id: cmd.Lira.JonGuiDataLiraTarget
proto: jon_shared_cmd_lira.proto
package: cmd.Lira
type: message
---

# JonGuiDataLiraTarget

**Source:** `jon_shared_cmd_lira.proto`

## Description

A data structure containing geographic coordinates (latitude, longitude, altitude), angular positioning (azimuth, elevation), distance, and a UUID identifier for LIRA target information sent via the Refine_target command.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | timestamp | int64 | >= 0 |
| 2 | target_longitude | double | >= -180, <= 180 |
| 3 | target_latitude | double | >= -90, <= 90 |
| 4 | target_altitude | double | >= -430, <= 100000 |
| 5 | target_azimuth | double | >= 0, < 360 |
| 6 | target_elevation | double | >= -90, <= 90 |
| 7 | distance | double | >= 0 |
| 8 | uuid_part1 | int32 | - |
| 9 | uuid_part2 | int32 | - |
| 10 | uuid_part3 | int32 | - |
| 11 | uuid_part4 | int32 | - |



## Interaction

- **Category:** :sensor


### Purpose

LIRA target data structure containing geographic and angular positioning





### Implementation Notes

This is a nested message type used within cmd.Lira.Refine_target, not a standalone command.



## Field Notes


### timestamp (#1)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds


### target_longitude (#2)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees


### target_latitude (#3)


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees


### target_altitude (#4)


#### Metadata

- **Semantic Type:** :distance
- **Unit:** meters


### target_azimuth (#5)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### target_elevation (#6)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### distance (#7)


#### Metadata

- **Semantic Type:** :distance
- **Unit:** meters


### uuid_part1 (#8)


#### Metadata

- **Semantic Type:** :raw


### uuid_part2 (#9)


#### Metadata

- **Semantic Type:** :raw


### uuid_part3 (#10)


#### Metadata

- **Semantic Type:** :raw


### uuid_part4 (#11)


#### Metadata

- **Semantic Type:** :raw



