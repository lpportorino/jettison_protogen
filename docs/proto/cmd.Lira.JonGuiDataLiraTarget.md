---
id: cmd.Lira.JonGuiDataLiraTarget
proto: jon_shared_cmd_lira.proto
package: cmd.Lira
type: message
---

# JonGuiDataLiraTarget

**Source:** `jon_shared_cmd_lira.proto`

## Description

A data structure containing geographic coordinates (latitude, longitude, altitude), angular positioning (azimuth, elevation), distance, and a UUID identifier for LIRA target information sent via the Refine_target command. This message encapsulates all positioning data required for target refinement operations in the LIRA (Laser-based Integrated Ranging and Acquisition) subsystem.

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
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

LIRA target data structure containing geographic and angular positioning for target refinement operations.


### Related State

- `ser.JonGuiDataLrf` - LRF subsystem state including `isRefining` flag <!-- NEEDS_REVIEW: verify exact state message name -->


### Related Commands

- [[proto/cmd.Lira.Refine_target]] - Parent command that wraps this data structure
- `cmd.Lrf.RefineOn` - Enables refine mode in the LRF subsystem
- `cmd.Lrf.RefineOff` - Disables refine mode





### Preconditions

- LIRA subsystem must be active
- Valid GPS fix available for geographic coordinates
- LRF measurement available for distance field


### Implementation Notes

This is a nested message type used within cmd.Lira.Refine_target, not a standalone command. The UUID fields (uuid_part1 through uuid_part4) combine to form a 128-bit unique identifier that correlates this target data with entries in the target tracking system.



## Field Notes


### timestamp (#1)

Monotonic timestamp in microseconds marking when the target data was captured. Used for correlating target measurements with system state at a specific point in time.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds


### target_longitude (#2)

Longitude of the target in decimal degrees (WGS84). Combined with latitude and altitude to form the complete geographic position of the target.


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees


### target_latitude (#3)

Latitude of the target in decimal degrees (WGS84). Combined with longitude and altitude to form the complete geographic position of the target.


#### Metadata

- **Semantic Type:** :coordinate-geo
- **Unit:** degrees


### target_altitude (#4)

Altitude of the target in meters above sea level (MSL). The constraint range spans from the Dead Sea shore (-430m, lowest point on Earth) to the Karman line (100km, edge of space).


#### Metadata

- **Semantic Type:** :distance
- **Unit:** meters


### target_azimuth (#5)

Azimuth angle to target in degrees (0=North, clockwise). Represents the horizontal bearing from the observation platform to the target. Can be displayed in degrees or NATO mils (6400 mils = 360 degrees) in the UI.


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### target_elevation (#6)

Elevation angle to target in degrees. Represents the vertical angle from the observation platform to the target. Positive values indicate targets above the horizon, negative values indicate targets below.


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### distance (#7)

Distance to target in meters as measured by the LRF (Laser Range Finder). Used in conjunction with azimuth and elevation angles to calculate the target's geographic position.


#### Metadata

- **Semantic Type:** :distance
- **Unit:** meters


### uuid_part1 (#8)

First 32-bit component of the 128-bit target UUID. Combined with parts 2-4 to form a unique identifier that correlates this target with entries in the system's target tracking database.


#### Metadata

- **Semantic Type:** :identifier


### uuid_part2 (#9)

Second 32-bit component of the 128-bit target UUID.


#### Metadata

- **Semantic Type:** :identifier


### uuid_part3 (#10)

Third 32-bit component of the 128-bit target UUID.


#### Metadata

- **Semantic Type:** :identifier


### uuid_part4 (#11)

Fourth 32-bit component of the 128-bit target UUID.


#### Metadata

- **Semantic Type:** :identifier



