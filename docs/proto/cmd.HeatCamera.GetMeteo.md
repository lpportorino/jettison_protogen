---
id: cmd.HeatCamera.GetMeteo
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Requests meteorological and diagnostic data from the thermal camera module. This parameterless fire-and-forget diagnostic command triggers the camera to return sensor readings and health metrics via state updates.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Request meteorological data from thermal camera sensors


### Related State

- [[proto/ser.JonGuiDataMeteo]]
- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.DayCamera.GetMeteo]]
- [[proto/cmd.Compass.GetMeteo]]
- [[proto/cmd.Gps.GetMeteo]]
- [[proto/cmd.Lrf.GetMeteo]]
- [[proto/cmd.RotaryPlatform.GetMeteo]]


### Preconditions

- Thermal camera must be started


### Implementation Notes

Polling command - retrieves environmental sensor data from the thermal camera's integrated sensors. The response includes temperature, humidity, and pressure readings that are used for environmental monitoring and ranging corrections.



