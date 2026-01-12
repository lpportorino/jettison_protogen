---
id: ser.JonGuiDataRotary
proto: jon_shared_data_rotary.proto
package: ser
type: message
---

# JonGuiDataRotary

**Source:** `jon_shared_data_rotary.proto`

## Description

Real-time rotary platform state including azimuth, elevation, and movement speeds. Provides comprehensive information about the platform's position, orientation, movement status, and scan operation progress.

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
| 9 | mode | [[ser.JonGuiDataRotaryMode]] | defined enum value only, not in: 0 |
| 10 | is_scanning | bool | - |
| 11 | is_scanning_paused | bool | - |
| 12 | use_rotary_as_compass | bool | - |
| 13 | scan_target | int32 | >= 0 |
| 14 | scan_target_max | int32 | >= 0 |
| 15 | sun_azimuth | double | >= 0, < 360 |
| 16 | sun_elevation | double | >= 0, < 360 |
| 17 | current_scan_node | [[ser.ScanNode]] | required |
| 18 | is_started | bool | - |

## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Update Rate:** Real-time

### Purpose

Provides real-time state information for the rotary platform subsystem. UI components should display position values, movement indicators, scan progress, and operational mode.

### Related Commands

- [[cmd.Rotary.MoveTo]] - Commands that modify azimuth/elevation
- [[cmd.Rotary.SetSpeed]] - Commands that modify movement speeds
- [[cmd.Rotary.StartScan]] - Commands that control scan operations
- [[cmd.Rotary.SetMode]] - Commands that change operational mode

### Display Guidelines

Display azimuth and elevation as primary indicators (compass/crosshair). Show movement speeds as directional arrows or velocity indicators. Scan progress should be shown as a progress bar (scan_target / scan_target_max). Platform orientation (bank) should be shown as a level indicator. Sun position can be overlaid on azimuth display for reference.

## Field Notes

### azimuth (#1)

Current turret azimuth angle relative to platform reference.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`
- **Range:** 0-360 (wraps)

### azimuth_speed (#2)

Current azimuth rotation speed (normalized).

#### Metadata

- **Semantic Type:** :speed-normalized
- **Unit:** -
- **Precision:** 2
- **Display Format:** `{value * 100}%`
- **Range:** -1 to 1 (negative = counterclockwise, positive = clockwise)

### elevation (#3)

Current turret elevation angle.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`
- **Range:** -90 to 90

### elevation_speed (#4)

Current elevation movement speed (normalized).

#### Metadata

- **Semantic Type:** :speed-normalized
- **Unit:** -
- **Precision:** 2
- **Display Format:** `{value * 100}%`
- **Range:** -1 to 1 (negative = down, positive = up)

### platform_azimuth (#5)

Platform's absolute azimuth orientation.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`
- **Range:** 0-360

### platform_elevation (#6)

Platform's elevation angle.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`

### platform_bank (#7)

Platform's bank/roll angle.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`
- **Range:** -180 to 180

### is_moving (#8)

True if platform is currently in motion.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show movement indicator/icon when true

### scan_target (#13)

Current scan progress counter.

#### Metadata

- **Semantic Type:** :count
- **Display Format:** `{scan_target} / {scan_target_max}`

### scan_target_max (#14)

Total scan points in current scan pattern.

#### Metadata

- **Semantic Type:** :count
- **Display Format:** Used in progress bar calculation

### sun_azimuth (#15)

Calculated sun azimuth position.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`
- **Range:** 0-360

### sun_elevation (#16)

Calculated sun elevation position.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`



