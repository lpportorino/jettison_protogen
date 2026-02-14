---
id: cmd.Compass.GetMeteo
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_compass.proto`

## Description

Requests meteorological sensor data (temperature, humidity, pressure) from the compass module's environmental sensors. This command is periodically requested by a system timer (every 600ms) rather than being triggered by user interaction, allowing continuous monitoring of environmental conditions.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Requests meteorological data (temperature, humidity, atmospheric pressure) from the compass module's integrated environmental sensors.


### Related State

- [[proto/ser.JonGuiDataCompass]] - Contains the `meteo` field populated by this command
- [[proto/ser.JonGuiDataMeteo]] - The meteorological data structure returned


### Related Commands

- [[proto/cmd.DayCamera.GetMeteo]] - Similar meteo request for day camera
- [[proto/cmd.HeatCamera.GetMeteo]] - Similar meteo request for thermal camera
- [[proto/cmd.Lrf.GetMeteo]] - Similar meteo request for LRF
- [[proto/cmd.Gps.GetMeteo]] - Similar meteo request for GPS


### Implementation Notes

This command is periodically requested by a system timer (every 600ms) rather than being triggered by user interaction, enabling continuous environmental monitoring. The response populates the `meteo` field in `ser.JonGuiDataCompass`, providing temperature, humidity, and pressure readings used for ballistics calculations and system diagnostics.


## Field Notes

This message has no fields - it is an empty request message. The compass module responds by updating the `meteo` field in the state broadcast (`ser.JonGuiDataCompass`).



