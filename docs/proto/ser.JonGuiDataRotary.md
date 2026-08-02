---
id: ser.JonGuiDataRotary
proto: jon_shared_data_rotary.proto
package: ser
type: message
---

# JonGuiDataRotary

**Source:** `jon_shared_data_rotary.proto`

## Description

Represents the real-time operational state of a rotary platform, tracking current position (azimuth, elevation, platform angles), motion characteristics (speeds and movement flags), scanning mode and progression, and auxiliary features (sun position data and compass integration mode).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | azimuth | double | >= 0, < 360 |
| 2 | azimuth_speed | double | >= -1, <= 1 |
| 3 | elevation | double | >= -90, <= 90 |
| 4 | elevation_speed | double | >= -1, <= 1 |
| 5 | platform_azimuth | double | >= 0, < 360 |
| 6 | platform_elevation | double | >= -90, <= 90 |
| 7 | platform_bank | double | >= -180, < 180 |
| 8 | is_moving | bool | - |
| 9 | mode | [[proto/ser.JonGuiDataRotaryMode]] | defined enum value only, not in: 0 |
| 10 | is_scanning | bool | - |
| 11 | is_scanning_paused | bool | - |
| 12 | use_rotary_as_compass | bool | - |
| 13 | scan_target | int32 | >= 0 |
| 14 | scan_target_max | int32 | >= 0 |
| 15 | sun_azimuth | double | >= 0, < 360 |
| 16 | sun_elevation | double | >= 0, < 360 |
| 17 | current_scan_node | [[proto/ser.ScanNode]] | - |
| 18 | is_started | bool | - |
| 19 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |
| 20 | pan_init_status | int32 | >= 0, <= 14 |
| 21 | tilt_init_status | int32 | >= 0, <= 14 |
| 22 | capture_monotonic_us | uint64 | - |
| 23 | is_parked | bool | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Real-time rotary platform position and motion state





### Implementation Notes

Read-only state message displaying azimuth, elevation, speeds, and operational mode



## Field Notes


### azimuth (#1)

Azimuth angle in degrees (0=North, clockwise)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 2
- **Display Format:** `{value}°`


### azimuth_speed (#2)

Current speed (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3
- **Display Format:** `{value}`


### elevation (#3)

Elevation angle in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 2
- **Display Format:** `{value}°`


### elevation_speed (#4)

Current speed (-1.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3
- **Display Format:** `{value}`


### platform_azimuth (#5)

Latitude in decimal degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 2
- **Display Format:** `{value}°`


### platform_elevation (#6)

Latitude in decimal degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 2
- **Display Format:** `{value}°`


### platform_bank (#7)

Latitude in decimal degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 2
- **Display Format:** `{value}°`


### mode (#9)

Operating mode


### scan_target (#13)

Current scan target node index


### scan_target_max (#14)

Maximum scan node count


### sun_azimuth (#15)

Azimuth angle in degrees (0=North, clockwise)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 2
- **Display Format:** `{value}°`


### sun_elevation (#16)

Sun elevation in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 2
- **Display Format:** `{value}°`


### current_scan_node (#17)

Current draw in amperes


### is_started (#18)

Whether the rotary platform is started.


### meteo (#19)

Local environmental sensor data from the rotary platform, providing temperature, humidity, and pressure readings for system diagnostics.


### pan_init_status (#20)

Initialization status code


### tilt_init_status (#21)

Initialization status code


### capture_monotonic_us (#22)

CLOCK_MONOTONIC timestamp in microseconds, stamped when state is pushed to SHM in the sync timer. Approximates when the data was last captured.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** us



