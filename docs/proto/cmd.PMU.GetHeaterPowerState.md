---
id: cmd.PMU.GetHeaterPowerState
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# GetHeaterPowerState

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Requests the current power state of the PMU's heater. Returns whether the heater is powered on or off.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :sensor
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Queries the heater power state. Response is delivered via the heater_power_state field in ser.JonGuiDataPMU.


### Related State

- [[proto/ser.JonGuiDataPMU#heater_power_state]]


### Related Commands

- [[proto/cmd.PMU.BootHeater]]


### Preconditions

- PMU must be started




