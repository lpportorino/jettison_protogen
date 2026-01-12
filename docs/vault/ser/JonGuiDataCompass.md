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
- **Update Rate:** Real-time (high frequency)

### Purpose

Provides real-time compass orientation data for the platform. UI components should display heading (azimuth), pitch (elevation), roll (bank), and calibration status. Essential for navigation, target acquisition, and platform stabilization.

### Related Commands

- [[cmd.Compass.SetOffset]] - Sets azimuth/elevation offsets
- [[cmd.Compass.SetMagneticDeclination]] - Sets magnetic declination correction
- [[cmd.Compass.StartCalibration]] - Initiates calibration procedure
- [[cmd.Compass.StopCalibration]] - Stops calibration
- [[cmd.Compass.Start]] - Starts compass subsystem
- [[cmd.Compass.Stop]] - Stops compass subsystem

### Display Guidelines

Display azimuth as primary heading indicator (compass rose or numeric). Show elevation and bank as secondary orientation indicators (artificial horizon or numeric). Display calibration status prominently (show warning if calibrating). Magnetic declination should be shown in settings/diagnostics view. Offsets are typically hidden from user (system calibration).

## Field Notes

### azimuth (#1)

Current compass heading (magnetic north reference).

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°` or compass rose
- **Range:** 0-360 (wraps, 0 = North, 90 = East, 180 = South, 270 = West)

### elevation (#2)

Current platform pitch angle.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`
- **Range:** -90 to 90 (negative = pitch down, positive = pitch up)

### bank (#3)

Current platform roll angle.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`
- **Range:** -180 to 180 (negative = roll left, positive = roll right)

### offsetAzimuth (#4)

Azimuth calibration offset applied to raw compass reading.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`
- **Note:** System calibration value, typically not displayed to end users

### offsetElevation (#5)

Elevation calibration offset applied to raw compass reading.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`
- **Note:** System calibration value, typically not displayed to end users

### magneticDeclination (#6)

Magnetic declination correction (difference between true and magnetic north).

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°` (positive = East, negative = West)
- **Range:** -180 to 180
- **Note:** Location-dependent, should be set based on geographic position

### calibrating (#7)

Calibration procedure in progress.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "Calibrating..." warning/indicator
- **Note:** User should perform rotation procedure when true

### is_started (#8)

Compass subsystem running state.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "Compass: Started/Stopped" or status indicator



