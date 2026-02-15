---
id: cmd.Lrf.GetMeteo
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Requests current meteorological data (temperature, humidity, pressure) from the laser rangefinder device. This command is periodically sent by the system to retrieve environmental sensor readings used for ranging corrections and environmental monitoring.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Requests meteorological data from LRF


### Related State

- [[proto/ser.JonGuiDataLrf]]
- [[proto/ser.JonGuiDataMeteo]] - Contains temperature, humidity, pressure readings


### Related Commands

- [[proto/cmd.DayCamera.GetMeteo]] - Meteo request for day camera
- [[proto/cmd.HeatCamera.GetMeteo]] - Meteo request for thermal camera
- [[proto/cmd.Compass.GetMeteo]] - Meteo request for compass
- [[proto/cmd.Gps.GetMeteo]] - Meteo request for GPS


### Preconditions

- LRF must be started


### Implementation Notes

Frontend function `getMeteo()` in `cmdLRF.ts` sends this command. Typically called periodically by the system to update environmental sensor readings used for atmospheric corrections in ranging calculations.


