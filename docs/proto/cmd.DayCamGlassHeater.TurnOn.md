---
id: cmd.DayCamGlassHeater.TurnOn
proto: jon_shared_cmd_day_cam_glass_heater.proto
package: cmd.DayCamGlassHeater
type: message
---

# TurnOn

**Source:** `jon_shared_cmd_day_cam_glass_heater.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataDayCamGlassHeater]]


### Related Commands

- [[proto/proto/cmd.DayCamGlassHeater.TurnOff]]


### Preconditions

- Glass heater subsystem must be started




