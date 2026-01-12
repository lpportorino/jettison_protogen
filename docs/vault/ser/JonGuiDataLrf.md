---
id: ser.JonGuiDataLrf
proto: jon_shared_data_lrf.proto
package: ser
type: message
---

# JonGuiDataLrf

**Source:** `jon_shared_data_lrf.proto`

## Description

Laser rangefinder state including distance, velocity, quality, and scan mode. Provides real-time measurement data, operational status, and targeting information from the LRF subsystem.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | is_scanning | bool | - |
| 2 | is_measuring | bool | - |
| 3 | measure_id | int32 | >= 0 |
| 4 | target | [[ser.JonGuiDataTarget]] | - |
| 5 | pointer_mode | [[ser.JonGuiDatatLrfLaserPointerModes]] | defined enum value only |
| 6 | fogModeEnabled | bool | - |
| 7 | is_refining | bool | - |
| 8 | is_continuous_measuring | bool | - |
| 9 | is_started | bool | - |

## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Update Rate:** Real-time

### Purpose

Provides real-time state information for the laser rangefinder subsystem. UI components should display measurement data (distance, target coordinates, quality metrics), operational status (scanning, measuring, refining), and laser pointer mode.

### Related Commands

- [[cmd.Lrf.Measure]] - Initiates single measurement
- [[cmd.Lrf.StartContinuousMeasure]] - Starts continuous measurements
- [[cmd.Lrf.StopContinuousMeasure]] - Stops continuous measurements
- [[cmd.Lrf.StartScan]] - Initiates scan operation
- [[cmd.Lrf.StopScan]] - Stops scan operation
- [[cmd.Lrf.SetPointerMode]] - Controls laser pointer mode
- [[cmd.Lrf.SetFogMode]] - Controls fog mode
- [[cmd.Lrf.Start]] - Starts LRF subsystem
- [[cmd.Lrf.Stop]] - Stops LRF subsystem

### Display Guidelines

Display primary measurement data prominently (distance from target field). Show measurement status indicators (is_measuring, is_scanning, is_refining). Display target coordinates when available. Show laser pointer mode and fog mode states. Update measurement ID to indicate new measurements.

## Field Notes

### is_scanning (#1)

LRF scan operation in progress.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "Scanning" indicator or icon

### is_measuring (#2)

LRF measurement in progress.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "Measuring" indicator or icon

### measure_id (#3)

Incremental measurement counter (increments with each new measurement).

#### Metadata

- **Semantic Type:** :sequence-number
- **Display Format:** `Measurement #{value}`

### target (#4)

Complete target measurement data including coordinates, distance, and metadata.

#### Metadata

- **Semantic Type:** :composite
- **Display Format:** Extract and display key fields (distance_2d, distance_3d, target coordinates)
- **Note:** See [[ser.JonGuiDataTarget]] for detailed field structure

### pointer_mode (#5)

Current laser pointer mode.

#### Metadata

- **Semantic Type:** :enum
- **Display Format:** Show mode name (e.g., "Off", "Continuous", "Pulsed")

### fogModeEnabled (#6)

Fog mode enabled state (enhances measurement accuracy in fog/haze).

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "Fog Mode: ON/OFF" or indicator

### is_refining (#7)

LRF performing measurement refinement.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "Refining" indicator or progress spinner

### is_continuous_measuring (#8)

Continuous measurement mode active.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "Continuous" indicator or mode badge

### is_started (#9)

LRF subsystem running state.

#### Metadata

- **Semantic Type:** :status-flag
- **Display Format:** Show as "LRF: Started/Stopped" or status indicator



