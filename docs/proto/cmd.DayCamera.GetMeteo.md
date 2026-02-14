---
id: cmd.DayCamera.GetMeteo
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Polling command that requests meteorological sensor data (temperature, humidity, pressure) from the day camera module. This is a parameterless fire-and-forget command that triggers an asynchronous response via state updates to JonGuiDataMeteo.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Request meteorological data from day camera sensors


### Related State

- [[proto/ser.JonGuiDataMeteo]]
- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.HeatCamera.GetMeteo]]
- [[proto/cmd.Lrf.GetMeteo]]
- [[proto/cmd.RotaryPlatform.GetMeteo]]
- [[proto/cmd.Compass.GetMeteo]]
- [[proto/cmd.Gps.GetMeteo]]


### Preconditions

- Day camera must be started


### Implementation Notes

Polling command - retrieves environmental sensor data from the day camera's integrated sensors. The response includes temperature, humidity, and pressure readings that are used for environmental monitoring and ranging corrections.



