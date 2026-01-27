---
id: cmd.PMU.BootHeater
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# BootHeater

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Powers on the PMU's onboard heater. Used for cold-weather operation to maintain safe operating temperatures.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout

### Purpose

Activates the PMU heater for cold-weather operation. The heater_power_state field in ser.JonGuiDataPMU will reflect the state.

### Related State

- [[proto/ser.JonGuiDataPMU]]

### Related Commands

- [[proto/cmd.PMU.GetHeaterPowerState]]

### Preconditions

- PMU must be started




