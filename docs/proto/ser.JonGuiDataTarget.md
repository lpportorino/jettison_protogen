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


### capture_type (#23)

Discriminates what the capture event actually WAS: a ranged **TARGET** or a **PHOTO**. Per the field's own comment, PHOTO covers two distinct situations — the operator issuing a Photo command, and an LRF measure that returned no valid range.

The consequence for a consumer is the part to hold onto: **on a PHOTO record no valid range exists**, so `distance_2d`, `distance_3b` and `distance_c` carry no measurement worth trusting whatever numbers they happen to hold. This field is how that is known without guessing, and it is why the discriminator exists at all.

Its constraint is `defined_only` and — unlike `observer_fix_type` (#13) in this same message, which additionally excludes 0 — it deliberately admits 0. `JON_GUI_DATA_TARGET_TYPE_UNSPECIFIED` is therefore a legal value here, and per the field comment it means exactly one thing: **the record predates the discriminator**. It is not "the system did not look", and it is not a third kind of capture. Nothing else in the record recovers which of TARGET or PHOTO an UNSPECIFIED row was, so surface it as historical data rather than defaulting it to either value — defaulting it silently reclassifies old captures.

`defined_only` means the number on the wire must be a member of [[proto/ser.JonGuiDataTargetType]] as this schema defines it; a value from a newer producer that this schema does not know is out of contract rather than something to pass through as an opaque number.

Production path, per [[proto/ser.JonGuiDataTargetType]]: each capture event is a `target_id` increment; manifold publishes this field from an internal `has_range` flag, and media_meta_pub consumes it to set the `media_items` `kind`, which drives the photo/target split in the media API and gallery. One field decides which bucket a capture lands in downstream.

Field 17 (`type`, `uint32`) is `reserved` in this message: its comment records that it was never written or read by any consumer and was retired in favour of this typed discriminator. `capture_type` is the only field here that carries the distinction.



