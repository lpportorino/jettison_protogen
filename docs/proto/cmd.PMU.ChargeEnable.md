---
id: cmd.PMU.ChargeEnable
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# ChargeEnable

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Enables battery charging. Allows the battery pack to charge from the external power source.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Enables battery charging. The charge_disabled field in ser.JonGuiDataPMU will become false.


### Related State

- [[proto/ser.JonGuiDataPMU]]


### Related Commands

- [[proto/cmd.PMU.ChargeDisable]]


### Preconditions

- PMU must be started
- External power source must be connected




