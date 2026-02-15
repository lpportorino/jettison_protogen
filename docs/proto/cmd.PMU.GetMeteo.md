---
id: cmd.PMU.GetMeteo
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Requests environmental/meteorological data from the PMU. Returns temperature, humidity, and pressure readings.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :sensor
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Requests current environmental sensor readings. Response is delivered via the meteo field in ser.JonGuiDataPMU.


### Related State

- [[proto/ser.JonGuiDataPMU]]
- [[proto/ser.JonGuiDataMeteo]]



### Related Commands

- [[proto/cmd.DayCamera.GetMeteo]] - Meteo from day camera
- [[proto/cmd.HeatCamera.GetMeteo]] - Meteo from thermal camera
- [[proto/cmd.Lrf.GetMeteo]] - Meteo from LRF


### Preconditions

- PMU must be started



