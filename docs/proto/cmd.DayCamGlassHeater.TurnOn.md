---
id: cmd.DayCamGlassHeater.TurnOn
proto: jon_shared_cmd_day_cam_glass_heater.proto
package: cmd.DayCamGlassHeater
type: message
---

# TurnOn

**Source:** `jon_shared_cmd_day_cam_glass_heater.proto`

## Description

Activates the day camera glass heater element to provide anti-fog and ice protection. This parameterless actuator command triggers the heating mechanism on the camera lens to prevent condensation and ice buildup in cold or humid conditions.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Turns on day camera glass heater


### Related State

- [[proto/proto/proto/ser.JonGuiDataDayCamGlassHeater]]


### Related Commands

- [[proto/proto/proto/cmd.DayCamGlassHeater.TurnOff]]


### Preconditions

- Glass heater subsystem must be started




