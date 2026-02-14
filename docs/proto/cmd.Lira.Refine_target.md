---
id: cmd.Lira.Refine_target
proto: jon_shared_cmd_lira.proto
package: cmd.Lira
type: message
---

# Refine_target

**Source:** `jon_shared_cmd_lira.proto`

## Description

Updates target tracking coordinates by accepting a refined target location with GPS coordinates (latitude, longitude, altitude), azimuth/elevation angles, distance, and UUID to store in the system's last_target tracking state. This command is used during "Refine Mode" to make precise targeting adjustments based on laser rangefinder (LRF) measurements combined with GPS and angular positioning data.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target | [[proto/cmd.Lira.JonGuiDataLiraTarget]] | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Refines target positioning using LIRA rangefinding data. When Refine Mode is active (controlled via `cmd.Lrf.RefineOn`/`cmd.Lrf.RefineOff`), this command submits updated target coordinates calculated from LRF distance measurements combined with observer position and angular data.


### Related Commands

- [[proto/cmd.Lrf.RefineOn]] - Enables refine mode (sets `ser.JonGuiDataLrf.isRefining` to true)
- [[proto/cmd.Lrf.RefineOff]] - Disables refine mode
- [[proto/cmd.Lrf.Measure]] - Triggers LRF distance measurement


### Related State

- [[proto/ser.JonGuiDataLrf]] - Contains `isRefining` flag indicating if refine mode is active
- [[proto/ser.JonGuiDataTarget]] - Contains the current target data including observer and target coordinates


### Preconditions

- LRF subsystem must be started (`ser.JonGuiDataLrf.isStarted` = true)
- Refine mode should be active (`ser.JonGuiDataLrf.isRefining` = true)
- Valid GPS fix available for accurate coordinate calculation
- Valid target data available from LRF measurement


### Implementation Notes

Complex command with geographic coordinates, angles, and UUID for target tracking. The target data includes both the computed target position (latitude, longitude, altitude) and the angular/distance measurements used to derive it (azimuth, elevation, distance). The UUID allows tracking multiple targets across sessions. <!-- NEEDS_REVIEW: Confirm whether this command is sent automatically during refine mode or requires explicit user action -->



## Field Notes


### target (#1)

Contains the complete LIRA target data structure with geographic coordinates (lat/lon/alt), angular positioning (azimuth/elevation), distance measurement, and a 128-bit UUID for target identification. See [[proto/cmd.Lira.JonGuiDataLiraTarget]] for detailed field descriptions.



