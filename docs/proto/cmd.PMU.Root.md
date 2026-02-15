---
id: cmd.PMU.Root
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# Root

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Root command container for the Power Management Unit. Contains all PMU-related commands as a required oneof including lifecycle control, charging, heater, and sensor queries.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start | [[proto/cmd.PMU.Start]] | - |
| 2 | stop | [[proto/cmd.PMU.Stop]] | - |
| 3 | turn_on | [[proto/cmd.PMU.TurnOn]] | - |
| 4 | turn_off | [[proto/cmd.PMU.TurnOff]] | - |
| 5 | get_meteo | [[proto/cmd.PMU.GetMeteo]] | - |
| 6 | get_heater_power_state | [[proto/cmd.PMU.GetHeaterPowerState]] | - |
| 7 | power_off | [[proto/cmd.PMU.PowerOff]] | - |
| 8 | charge_enable | [[proto/cmd.PMU.ChargeEnable]] | - |
| 9 | charge_disable | [[proto/cmd.PMU.ChargeDisable]] | - |
| 10 | boot_heater | [[proto/cmd.PMU.BootHeater]] | - |
| 11 | get_data_u1 | [[proto/cmd.PMU.GetDataU1]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8, #9, #10, #11




## Interaction

- **Category:** :actuator
- **UI Pattern:** :command-router
- **Feedback:** :fire-and-forget


### Purpose

Container message that wraps all PMU commands. Exactly one command must be set.


### Related State

- [[proto/ser.JonGuiDataPMU]]






## Field Notes


### start (#1)

See [[proto/cmd.PMU.Start]]


### stop (#2)

See [[proto/cmd.PMU.Stop]]


### turn_on (#3)

See [[proto/cmd.PMU.TurnOn]]


### turn_off (#4)

See [[proto/cmd.PMU.TurnOff]]


### get_meteo (#5)

See [[proto/cmd.PMU.GetMeteo]]


### get_heater_power_state (#6)

See [[proto/cmd.PMU.GetHeaterPowerState]]


### power_off (#7)

See [[proto/cmd.PMU.PowerOff]]


### charge_enable (#8)

See [[proto/cmd.PMU.ChargeEnable]]


### charge_disable (#9)

See [[proto/cmd.PMU.ChargeDisable]]


### boot_heater (#10)

See [[proto/cmd.PMU.BootHeater]]


### get_data_u1 (#11)

See [[proto/cmd.PMU.GetDataU1]]



