---
id: cmd.DayCamGlassHeater.Root
proto: jon_shared_cmd_day_cam_glass_heater.proto
package: cmd.DayCamGlassHeater
type: message
---

# Root

**Source:** `jon_shared_cmd_day_cam_glass_heater.proto`

## Description

Root command container for the day camera glass heater device using a required oneof pattern. Dispatches between start, stop, turn on, turn off, and get meteorological data commands to control the heater that prevents lens fogging and ice formation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start | [[proto/cmd.DayCamGlassHeater.Start]] | - |
| 2 | stop | [[proto/cmd.DayCamGlassHeater.Stop]] | - |
| 3 | turn_on | [[proto/cmd.DayCamGlassHeater.TurnOn]] | - |
| 4 | turn_off | [[proto/cmd.DayCamGlassHeater.TurnOff]] | - |
| 5 | get_meteo | [[proto/cmd.DayCamGlassHeater.GetMeteo]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5




## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Root command container for day camera glass heater





### Implementation Notes

Not directly invoked - contains nested commands



