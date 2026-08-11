---
id: ser.JonGuiDataCompass
proto: jon_shared_data_compass.proto
package: ser
type: message
---

# JonGuiDataCompass

**Source:** `jon_shared_data_compass.proto`

## Description

Represents the real-time orientation and calibration state of a compass sensor, containing directional measurements (azimuth, elevation, bank angles), calibration offsets, magnetic declination, and status flags for whether the compass is running and calibrating.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | azimuth | double | >= 0, < 360 |
| 2 | elevation | double | >= -90, <= 90 |
| 3 | bank | double | >= -180, < 180 |
| 4 | offsetAzimuth | double | >= -180, < 180 |
| 5 | offsetElevation | double | >= -90, <= 90 |
| 6 | magneticDeclination | double | >= -180, < 180 |
| 7 | calibrating | bool | - |
| 8 | is_started | bool | - |
| 9 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Compass sensor data and calibration state



### Related Commands

- [[proto/cmd.Compass.Start]]
- [[proto/cmd.Compass.Stop]]
- [[proto/cmd.Compass.CalibrateStartLong]]





## Field Notes


### azimuth (#1)

Azimuth angle in degrees (0=North, clockwise)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### elevation (#2)

Elevation angle in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### bank (#3)

Bank/roll angle in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### offsetAzimuth (#4)

Azimuth offset correction in degrees


### offsetElevation (#5)

Elevation angle in degrees


### magneticDeclination (#6)

Magnetic declination correction in degrees


### is_started (#8)

Whether the compass is started.


### meteo (#9)

Local environmental sensor data from the compass module, providing temperature, humidity, and pressure readings for system diagnostics.



