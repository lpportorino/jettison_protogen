---
id: ser.JonGuiDataTarget
proto: jon_shared_data_lrf.proto
package: ser
type: message
---

# JonGuiDataTarget

**Source:** `jon_shared_data_lrf.proto`

## Description

Encodes a single laser rangefinder (LRF) measurement with the geographic coordinates of the detected target and the observer's position, orientation, and GPS fix quality, along with computed 2D and 3D distances and visual properties for UI display. Serves as the core data structure for target tracking in the GUI, enabling real-time visualization of LRF measurements on maps with color-coded targets.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | timestamp | int64 | >= 0 |
| 2 | target_longitude | double | >= -180, <= 180 |
| 3 | target_latitude | double | >= -90, <= 90 |
| 4 | target_altitude | double | - |
| 5 | observer_longitude | double | >= -180, <= 180 |
| 6 | observer_latitude | double | >= -90, <= 90 |
| 7 | observer_altitude | double | - |
| 8 | observer_azimuth | double | >= 0, < 360 |
| 9 | observer_elevation | double | >= -90, <= 90 |
| 10 | observer_bank | double | >= -180, < 180 |
| 11 | distance_2d | double | >= 0, <= 500000 |
| 12 | distance_3b | double | >= 0, <= 500000 |
| 22 | distance_c | double | >= 0, <= 500000 |
| 13 | observer_fix_type | [[proto/ser.JonGuiDataGpsFixType]] | defined enum value only, not in: 0 |
| 14 | session_id | int32 | >= 0 |
| 15 | target_id | int32 | >= 0 |
| 16 | target_color | [[proto/ser.RgbColor]] | - |
| 18 | uuid_part1 | int32 | - |
| 19 | uuid_part2 | int32 | - |
| 20 | uuid_part3 | int32 | - |
| 21 | uuid_part4 | int32 | - |
| 23 | capture_type | [[proto/ser.JonGuiDataTargetType]] | defined enum value only |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Feedback:** :poll-confirm


### Purpose

Target tracking and designation data





### Implementation Notes

Displays tracked target information, coordinates, and designation status



## Field Notes


### timestamp (#1)

Monotonic timestamp in microseconds


### target_longitude (#2)

Longitude in decimal degrees


### target_latitude (#3)

Latitude in decimal degrees


### observer_longitude (#5)

Longitude in decimal degrees


### observer_latitude (#6)

Latitude in decimal degrees


### observer_altitude (#7)

Observer altitude


### observer_azimuth (#8)

Azimuth angle in degrees (0=North, clockwise)


### observer_elevation (#9)

Elevation angle in degrees


### observer_bank (#10)

Bank/roll angle in degrees


### distance_2d (#11)

Calculated distance to target in meters


### distance_3b (#12)

Calculated distance to target in meters


### distance_c (#22)

Calculated distance to target in meters


### observer_fix_type (#13)

See related enum for valid values


### session_id (#14)

Session identifier


### target_id (#15)

Target tracking identifier


### target_color (#16)

See [[proto/ser.RgbColor]]


### uuid_part1 (#18)

UUID component (combined parts form full UUID)


### uuid_part2 (#19)

UUID component (combined parts form full UUID)


### uuid_part3 (#20)

UUID component (combined parts form full UUID)


### uuid_part4 (#21)

UUID component (combined parts form full UUID)



