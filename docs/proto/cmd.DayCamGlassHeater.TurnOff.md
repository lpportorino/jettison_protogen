---
id: cmd.DayCamGlassHeater.TurnOff
proto: jon_shared_cmd_day_cam_glass_heater.proto
package: cmd.DayCamGlassHeater
type: message
---

# TurnOff

**Source:** `jon_shared_cmd_day_cam_glass_heater.proto`

## Description

Turns off the day camera glass heater element, disabling its anti-fog and ice protection functionality. This actuator command deactivates heating while keeping the heater subsystem running, allowing quick reactivation via TurnOn without restarting the module.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Turn off day camera glass heater to disable anti-fog/ice protection


### Related State

- [[proto/proto/proto/ser.JonGuiDataDayCamGlassHeater]]


### Related Commands

- [[proto/proto/proto/cmd.DayCamGlassHeater.TurnOn]]
- [[proto/proto/proto/cmd.DayCamGlassHeater.Start]]
- [[proto/proto/proto/cmd.DayCamGlassHeater.Stop]]





