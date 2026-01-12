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

Requests meteorological/diagnostic data from thermal camera



### Related Commands

- [[proto/proto/cmd.Compass.GetMeteo]]
- [[proto/proto/cmd.Gps.GetMeteo]]
- [[proto/proto/cmd.RotaryPlatform.GetMeteo]]





