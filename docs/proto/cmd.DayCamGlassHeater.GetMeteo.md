---
id: cmd.DayCamGlassHeater.GetMeteo
proto: jon_shared_cmd_day_cam_glass_heater.proto
package: cmd.DayCamGlassHeater
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_day_cam_glass_heater.proto`

## Description

Requests meteorological sensor data from the day camera glass heater system. This parameterless diagnostic command triggers the system to query and return weather-related sensor readings such as temperature and humidity for monitoring heater conditions.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Requests meteorological data from day camera glass heater


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataDayCamGlassHeater]]
- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataMeteo]]




### Implementation Notes

Diagnostic command to query sensor data



