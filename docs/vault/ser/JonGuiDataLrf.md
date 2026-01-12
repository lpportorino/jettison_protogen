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


### Purpose

Provides real-time state information for the laser rangefinder subsystem. UI components should display measurement data (distance, target coordinates, quality metrics), operational status (scanning, measuring, refining), and laser pointer mode.



### Related Commands

- [[cmd.Lrf.Measure]]
- [[cmd.Lrf.StartContinuousMeasure]]
- [[cmd.Lrf.StopContinuousMeasure]]
- [[cmd.Lrf.StartScan]]
- [[cmd.Lrf.StopScan]]
- [[cmd.Lrf.SetPointerMode]]
- [[cmd.Lrf.SetFogMode]]
- [[cmd.Lrf.Start]]
- [[cmd.Lrf.Stop]]





## Field Notes


### is_scanning (#1)

LRF scan operation in progress.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Scanning&quot; indicator or icon`


### is_measuring (#2)

LRF measurement in progress.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Measuring&quot; indicator or icon`


### measure_id (#3)

Incremental measurement counter (increments with each new measurement).


#### Metadata

- **Semantic Type:** :count
- **Display Format:** `Measurement #{value}`


### target (#4)

Complete target measurement data including coordinates, distance, and metadata.


#### Metadata

- **Semantic Type:** :raw
- **Display Format:** `Extract and display key fields (distance_2d, distance_3d, target coordinates)`


### pointer_mode (#5)

Current laser pointer mode.


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `Show mode name (e.g., &quot;Off&quot;, &quot;Continuous&quot;, &quot;Pulsed&quot;)`


### fogModeEnabled (#6)

Fog mode enabled state (enhances measurement accuracy in fog/haze).


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Fog Mode: ON/OFF&quot; or indicator`


### is_refining (#7)

LRF performing measurement refinement.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Refining&quot; indicator or progress spinner`


### is_continuous_measuring (#8)

Continuous measurement mode active.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;Continuous&quot; indicator or mode badge`


### is_started (#9)

LRF subsystem running state.


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Show as &quot;LRF: Started/Stopped&quot; or status indicator`



