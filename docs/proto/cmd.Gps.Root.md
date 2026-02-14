---
id: cmd.Gps.Root
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# Root

**Source:** `jon_shared_cmd_gps.proto`

## Description

Root command container for GPS module operations using a required oneof pattern. Dispatches between five command types: start, stop, set manual position, toggle manual position mode, and get meteorological data for GPS lifecycle and configuration management.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start | [[proto/cmd.Gps.Start]] | - |
| 2 | stop | [[proto/cmd.Gps.Stop]] | - |
| 3 | set_manual_position | [[proto/cmd.Gps.SetManualPosition]] | - |
| 4 | set_use_manual_position | [[proto/cmd.Gps.SetUseManualPosition]] | - |
| 5 | get_meteo | [[proto/cmd.Gps.GetMeteo]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5




## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :command-router
- **Feedback:** :fire-and-forget


### Purpose

Container for GPS module commands. Routes to sub-commands for starting/stopping the GPS receiver, configuring manual position fallback, and requesting meteorological data.


### Related State

- [[proto/ser.JonGuiDataGps]]


### Related Commands

- [[proto/cmd.Gps.Start]]
- [[proto/cmd.Gps.Stop]]
- [[proto/cmd.Gps.SetManualPosition]]
- [[proto/cmd.Gps.SetUseManualPosition]]
- [[proto/cmd.Gps.GetMeteo]]


### Implementation Notes

Root message containing GPS start/stop and configuration. Used as a wrapper in the command hierarchy - all GPS commands are sent by creating a `cmd.Gps.Root` with the appropriate oneof field set. The frontend dispatches through `cmdGps.ts` functions: `gpsStart()`, `gpsStop()`, `setManualPosition()`, `setUseManualPosition()`, and `getMeteo()`.



## Field Notes


### start (#1)

See [[proto/cmd.Gps.Start]]


### stop (#2)

See [[proto/cmd.Gps.Stop]]


### set_manual_position (#3)

See [[proto/cmd.Gps.SetManualPosition]]


### set_use_manual_position (#4)

See [[proto/cmd.Gps.SetUseManualPosition]]


### get_meteo (#5)

See [[proto/cmd.Gps.GetMeteo]]



