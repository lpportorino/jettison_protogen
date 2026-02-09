---
id: cmd.Heater.Root
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# Root

**Source:** `jon_shared_cmd_heater.proto`

## Description

Root command container for the heater subsystem. Contains all heater-related commands as a required oneof.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start | [[proto/cmd.Heater.Start]] | - |
| 2 | stop | [[proto/cmd.Heater.Stop]] | - |
| 3 | set_heating | [[proto/cmd.Heater.SetHeating]] | - |
| 4 | get_status | [[proto/cmd.Heater.GetStatus]] | - |
| 5 | enable_automatic_control | [[proto/cmd.Heater.EnableAutomaticControl]] | - |
| 6 | disable_automatic_control | [[proto/cmd.Heater.DisableAutomaticControl]] | - |
| 7 | set_automatic_control_params | [[proto/cmd.Heater.SetAutomaticControlParams]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7




## Interaction

- **Category:** :settings
- **UI Pattern:** :tabbed-config


### Purpose

Container message that wraps all heater commands. Exactly one command must be set.


### Related State

- [[proto/ser.JonGuiDataHeater]]






