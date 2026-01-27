---
id: cmd.PMU.ChargeDisable
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# ChargeDisable

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Disables battery charging. Prevents the battery pack from charging even when external power is connected.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout

### Purpose

Disables battery charging. The charge_disabled field in ser.JonGuiDataPMU will become true.

### Related State

- [[proto/ser.JonGuiDataPMU]]

### Related Commands

- [[proto/cmd.PMU.ChargeEnable]]

### Preconditions

- PMU must be started




