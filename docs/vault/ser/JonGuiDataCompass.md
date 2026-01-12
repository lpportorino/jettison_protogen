---
id: ser.JonGuiDataCompass
proto: jon_shared_data_compass.proto
package: ser
type: message
---

# JonGuiDataCompass

**Source:** `jon_shared_data_compass.proto`

## Description

Compass state including heading, declination, and calibration status. Provides real-time orientation data from the digital compass including azimuth, elevation, bank angles, and offset corrections.

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



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator


### Purpose

Provides real-time compass orientation data for the platform. UI components should display heading (azimuth), pitch (elevation), roll (bank), and calibration status. Essential for navigation, target acquisition, and platform stabilization.



### Related Commands

- [[cmd.Compass.SetOffset]]
- [[cmd.Compass.SetMagneticDeclination]]
- [[cmd.Compass.StartCalibration]]
- [[cmd.Compass.StopCalibration]]
- [[cmd.Compass.Start]]
- [[cmd.Compass.Stop]]





## Field Notes


### azimuth (#1)

Current compass heading (magnetic north reference).


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}° or compass rose`


### elevation (#2)

Current platform pitch angle.


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`


### bank (#3)

Current platform roll angle.


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`


### offsetAzimuth (#4)

Azimuth calibration offset applied to raw compass reading.


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`


### offsetElevation (#5)

Elevation calibration offset applied to raw compass reading.


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`


### magneticDeclination (#6)

Magnetic declination correction (difference between true and magnetic north).


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}° (positive = East, negative = West)`


### calibrating (#7)

Calibration procedure in progress.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Calibrating...&quot; warning/indicator`


### is_started (#8)

Compass subsystem running state.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Compass: Started/Stopped&quot; or status indicator`



