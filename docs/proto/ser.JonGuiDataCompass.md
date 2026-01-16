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


### Purpose

Compass sensor data and calibration state



### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.Compass.Start]]
- [[proto/proto/proto/proto/proto/proto/cmd.Compass.Stop]]
- [[proto/proto/proto/proto/proto/proto/cmd.Compass.CalibrateStartLong]]





## Field Notes


### azimuth (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### elevation (#2)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees


### bank (#3)


#### Metadata

- **Semantic Type:** :enum-label


### meteo (#9)

Local environmental sensor data from the compass module, providing temperature, humidity, and pressure readings for system diagnostics.


