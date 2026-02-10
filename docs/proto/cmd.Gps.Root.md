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
- **UI Pattern:** :tabbed-config


### Purpose

Container for GPS module commands


### Related State

- [[proto/ser.JonGuiDataGps]]




### Implementation Notes

Root message containing GPS start/stop and configuration



