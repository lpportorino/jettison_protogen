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

- [[proto/ser.JonGuiDataLrf#meteo]]



### Preconditions

- LRF must be started




